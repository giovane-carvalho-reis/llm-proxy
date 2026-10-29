package dev.giovane.llmproxy.service;

import dev.giovane.llmproxy.config.ProxyProperties;

import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

/** The two RestClients (chat, catalog) plus the shared POST/GET plumbing and header hygiene. */
@Component
public class UpstreamHttp {

    // RFC 7230 §6.1 hop-by-hop headers. forward() buffers the response body into a String
    // (toEntity(String.class)) before Tomcat writes it, so Tomcat computes its own
    // Transfer-Encoding/Content-Length — forwarding the upstream's Transfer-Encoding verbatim
    // makes the servlet response carry two, which downstream HTTP clients (e.g. the BFF's
    // RestClient) reject with "multiple Transfer-Encoding headers". The SSE relay strips the
    // same set for the same reason, even though it isn't buffering.
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade");

    private final RestClient chat;
    private final RestClient catalog;

    UpstreamHttp(ProxyProperties props) {
        // llama.cpp prefill on big contexts is slow; give the upstream a long read timeout
        // (the controller thread blocks, matching the client's own long timeout).
        // StringHttpMessageConverter defaults to ISO-8859-1 when the upstream's Content-Type has
        // no charset param (llama.cpp/llama-swap's "application/json" doesn't set one) — that
        // silently mojibakes every non-ASCII char before it reaches downstream consumers.
        var utf8 = new StringHttpMessageConverter(StandardCharsets.UTF_8);
        this.chat = buildClient(props.routing().readTimeout(), utf8);
        // N4/D6: catálogo OpenRouter não herda o read-timeout de chat (15 min).
        this.catalog = buildClient(props.routing().catalogReadTimeout(), utf8);
    }

    private static RestClient buildClient(Duration readTimeout, StringHttpMessageConverter utf8) {
        return RestClient.builder()
                .requestFactory(ClientHttpRequestFactories.get(
                        ClientHttpRequestFactorySettings.DEFAULTS.withReadTimeout(readTimeout)))
                .messageConverters(converters -> {
                    converters.removeIf(c -> c instanceof StringHttpMessageConverter);
                    converters.add(0, utf8);
                })
                .build();
    }

    /** POST JSON com o Bearer do upstream — o par comum a forward e ao relay SSE. */
    RestClient.RequestBodySpec post(ProxyProperties.Upstream up, String path) {
        var spec = chat.post()
                .uri(up.baseUrl() + path)
                .contentType(MediaType.APPLICATION_JSON);
        if (up.apiKey() != null && !up.apiKey().isBlank()) {
            spec = spec.header("Authorization", "Bearer " + up.apiKey());
        }
        return spec;
    }

    /** GET com o cliente de chat (timeout longo). */
    ResponseEntity<String> get(String url) {
        return relayGet(chat, url);
    }

    /** GET com o cliente de catálogo (timeout curto). */
    ResponseEntity<String> getCatalog(String url) {
        return relayGet(catalog, url);
    }

    private static ResponseEntity<String> relayGet(RestClient client, String url) {
        return stripHopByHopHeaders(client.get()
                .uri(url)
                .retrieve()
                .onStatus(s -> true, (req, res) -> { })  // ADR-012: relay non-2xx as-is
                .toEntity(String.class));
    }

    static boolean isHopByHop(String headerName) {
        return HOP_BY_HOP_HEADERS.contains(headerName.toLowerCase());
    }

    static ResponseEntity<String> stripHopByHopHeaders(ResponseEntity<String> upstream) {
        HttpHeaders headers = new HttpHeaders();
        upstream.getHeaders().forEach((name, values) -> {
            if (!isHopByHop(name)) {
                headers.put(name, values);
            }
        });
        // The body was already decoded as UTF-8. But llama.cpp/llama-swap's Content-Type carries
        // no charset param, and Spring MVC's outbound StringHttpMessageConverter defaults to
        // ISO-8859-1 when none is declared — pin the charset explicitly.
        MediaType contentType = headers.getContentType();
        if (contentType != null && contentType.getCharset() == null) {
            headers.setContentType(new MediaType(contentType, StandardCharsets.UTF_8));
        }
        return new ResponseEntity<>(upstream.getBody(), headers, upstream.getStatusCode());
    }
}
