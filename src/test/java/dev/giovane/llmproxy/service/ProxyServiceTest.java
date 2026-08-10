package dev.giovane.llmproxy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import dev.giovane.llmproxy.config.LlmConfigState;
import dev.giovane.llmproxy.config.ProxyProperties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises ProxyService against a real (JDK-builtin) local HTTP server standing in for the
 * upstream, since ProxyService talks HTTP directly via RestClient rather than through an
 * injectable client.
 */
class ProxyServiceTest {

    private HttpServer upstream;
    private final AtomicReference<String> lastAuthHeader = new AtomicReference<>();
    private final AtomicReference<String> lastModel = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void startUpstream() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.createContext("/v1/chat/completions", exchange -> {
            lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastPath.set(exchange.getRequestURI().getPath());
            var body = mapper.readTree(exchange.getRequestBody());
            lastModel.set(body.path("model").asText(null));
            byte[] response = "{\"ok\":true}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        upstream.createContext("/v1/embeddings", exchange -> {
            lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            var body = mapper.readTree(exchange.getRequestBody());
            lastModel.set(body.path("model").asText(null));
            byte[] response = "{\"ok\":true}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        upstream.start();
    }

    @AfterEach
    void stopUpstream() {
        upstream.stop(0);
    }

    private String baseUrl() {
        return "http://localhost:" + upstream.getAddress().getPort();
    }

    private ProxyProperties props(String envApiKey, String envModel) {
        ProxyProperties.Upstream up = new ProxyProperties.Upstream(baseUrl(), envApiKey, envModel);
        return new ProxyProperties(up, up, up, up, new ProxyProperties.Routing(8000, Duration.ofMinutes(1)), "");
    }

    @Test
    void completionsUsesOverriddenApiKeyAndModelWhenSet() throws Exception {
        ProxyProperties props = props("env-key", "env-model");
        LlmConfigState configState = new LlmConfigState();
        configState.setOpenrouterApiKey("override-key");
        configState.setOpenrouterDefaultModel("override-model");
        ProxyService service = new ProxyService(props, configState, mapper);

        service.completions("{\"messages\":[]}", "openrouter", null);

        assertThat(lastAuthHeader.get()).isEqualTo("Bearer override-key");
        assertThat(lastModel.get()).isEqualTo("override-model");
    }

    @Test
    void completionsUsesEnvValuesWhenNoOverrideIsSet() throws Exception {
        ProxyProperties props = props("env-key", "env-model");
        LlmConfigState configState = new LlmConfigState();
        ProxyService service = new ProxyService(props, configState, mapper);

        service.completions("{\"messages\":[]}", "openrouter", null);

        assertThat(lastAuthHeader.get()).isEqualTo("Bearer env-key");
        assertThat(lastModel.get()).isEqualTo("env-model");
    }

    @Test
    void embeddingsIgnoresConfigStateOverridesEntirely() throws Exception {
        ProxyProperties props = props("env-key", "env-embed-model");
        LlmConfigState configState = new LlmConfigState();
        configState.setOpenrouterApiKey("override-key");
        configState.setOpenrouterDefaultModel("override-model");
        ProxyService service = new ProxyService(props, configState, mapper);

        service.embeddings("{}");

        assertThat(lastAuthHeader.get()).isEqualTo("Bearer env-key");
        assertThat(lastModel.get()).isEqualTo("env-embed-model");
    }
}
