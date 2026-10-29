package dev.giovane.llmproxy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.giovane.llmproxy.config.ProxyProperties;
import dev.giovane.llmproxy.upstream.UpstreamFailure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Real SSE passthrough for {@code stream: true} chat completions — same shape as
 * lazy-invest-bff's {@code ProxyController.proxyChatStream}. {@code exchange(fn, false)}
 * keeps the upstream connection open past the callback (the {@code false} disables
 * RestClient's default auto-close), so the {@link StreamingResponseBody} can read and
 * forward the body lazily, on Spring MVC's async dispatch thread, instead of being read
 * eagerly like the buffered non-stream path.
 */
@Component
class SseRelay {

    private static final Logger log = LoggerFactory.getLogger(SseRelay.class);
    private static final int MAX_SSE_LINE_BYTES = 64 * 1024;

    private final UpstreamHttp http;
    private final RequestLogger requestLog;
    private final ObjectMapper mapper;

    SseRelay(UpstreamHttp http, RequestLogger requestLog, ObjectMapper mapper) {
        this.http = http;
        this.requestLog = requestLog;
        this.mapper = mapper;
    }

    ResponseEntity<StreamingResponseBody> forward(ProxyProperties.Upstream up, String path, String payload,
            String target, ObjectNode request) {
        var spec = http.post(up, path);
        long startedAt = System.currentTimeMillis();
        try {
            return spec.body(payload).exchange((req, res) ->
                    ResponseEntity.status(res.getStatusCode()).headers(relayedHeaders(res))
                            .body((StreamingResponseBody) out -> copy(res, out, target, request, startedAt)), false);
        } catch (RestClientException e) {
            // SPEC-chat-backlog C.C2: a fase 2 do chat é stream — sem isto o alarme do Loki nunca via o backend caído.
            if (UpstreamFailure.classify(e) != null) {
                requestLog.backendDownThrottled(up.baseUrl(), path, e);
            }
            throw e;
        }
    }

    private static HttpHeaders relayedHeaders(ClientHttpResponse res) {
        HttpHeaders headers = new HttpHeaders();
        res.getHeaders().forEach((name, values) -> {
            // Content-Length also excluded here (unlike the buffered path, which keeps it because
            // the buffered body has that exact byte length): a compliant SSE response never
            // declares one upfront, so forwarding a stale value would make Tomcat frame the
            // chunked body wrong instead of computing its own framing.
            if (!UpstreamHttp.isHopByHop(name) && !"content-length".equalsIgnoreCase(name)) {
                headers.put(name, values);
            }
        });
        return headers;
    }

    /** Estado mutável de um stream em andamento (linha parcial, último chunk com usage, desfecho, bytes). */
    private static final class StreamState {
        final ByteArrayOutputStream line = new ByteArrayOutputStream();
        JsonNode usageChunk;
        String outcome = "ok";
        long bytes;
    }

    private void copy(ClientHttpResponse res, OutputStream out, String target, ObjectNode request,
            long startedAt) throws IOException {
        // SPEC-chat-backlog C.I3: só o último evento "data:" cujo usage é objeto (o resto do stream não fica
        // em memória); a linha é decodificada inteira, então multibyte cortado entre leituras não corrompe o JSON.
        StreamState st = new StreamState();
        try {
            try (InputStream in = res.getBody()) {
                pump(in, out, target, request, st);
            }
        } catch (IOException closeFailed) {
            // Falha só ao fechar o body upstream: já entregue, sem efeito no outcome.
            log.debug("[chat-stream] close failed | route={} model={}", target,
                    request.path("model").asText("?"), closeFailed);
        } finally {
            requestLog.streamSummary(target, request, st.usageChunk, res.getStatusCode().value(),
                    System.currentTimeMillis() - startedAt, st.outcome);
        }
    }

    private void pump(InputStream in, OutputStream out, String target, ObjectNode request, StreamState st) {
        byte[] buf = new byte[512];
        int n;
        while (true) {
            try {
                n = in.read(buf);
            } catch (IOException upstreamBroken) {
                // N1/D5: o read é o lado upstream — conexão cortada com o backend é falha dele, não do cliente.
                st.outcome = "upstream_interrompido";
                log.error("backend indisponível | stream upstream interrompido | route={} model={} bytes={} causa={}",
                        target, request.path("model").asText("?"), st.bytes, upstreamBroken.toString());
                return;
            }
            if (n == -1) return;
            st.bytes += n;
            try {
                out.write(buf, 0, n);
                out.flush(); // each SSE event must reach the client as it arrives, not batched
            } catch (IOException clientGone) {
                st.outcome = "cliente_desconectou";
                log.debug("[chat-stream] client disconnected mid-stream | route={} model={}",
                        target, request.path("model").asText("?"), clientGone);
                return;
            }
            for (int i = 0; i < n; i++) {
                feedByte(buf[i], st);
            }
        }
    }

    private void feedByte(byte b, StreamState st) {
        if (b != '\n') {
            // Teto por linha: uma linha SSE enorme sem \n não cresce sem limite (a de usage é curta).
            if (st.line.size() < MAX_SSE_LINE_BYTES) st.line.write(b);
            return;
        }
        JsonNode chunk = usageChunkOf(st.line.toString(StandardCharsets.UTF_8));
        if (chunk != null) st.usageChunk = chunk;
        st.line.reset();
    }

    /** N6: o evento só vale se {@code usage} for objeto — {@code "usage":null} dos chunks intermediários não conta. */
    private JsonNode usageChunkOf(String text) {
        if (!text.startsWith("data:") || !text.contains("\"usage\"")) {
            return null;
        }
        try {
            JsonNode chunk = mapper.readTree(text.substring(5).trim());
            return chunk.path("usage").isObject() ? chunk : null;
        } catch (IOException malformed) {
            return null;  // chunk malformado — ignora
        }
    }
}
