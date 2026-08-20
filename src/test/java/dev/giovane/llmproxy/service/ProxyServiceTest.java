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
    private final AtomicReference<com.fasterxml.jackson.databind.JsonNode> lastMessages = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastTemperature = new AtomicReference<>();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void startUpstream() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.createContext("/v1/chat/completions", exchange -> {
            lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastPath.set(exchange.getRequestURI().getPath());
            var body = mapper.readTree(exchange.getRequestBody());
            lastModel.set(body.path("model").asText(null));
            lastTemperature.set(body.has("temperature") ? body.path("temperature").asText() : null);
            lastMessages.set(body.path("messages"));
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
        upstream.createContext("/v1/models", exchange -> {
            byte[] response = ("{\"data\":[{\"id\":\"bge-m3\"},{\"id\":\"qwen3-14b\"},"
                    + "{\"id\":\"bge-reranker-v2-m3\"},{\"id\":\"qwen3.6-35b\"}]}").getBytes();
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
        return props(envApiKey, envModel, null);
    }

    private ProxyProperties props(String envApiKey, String envModel, Double temperature) {
        ProxyProperties.Upstream up = new ProxyProperties.Upstream(baseUrl(), envApiKey, envModel, temperature);
        return new ProxyProperties(up, up, up, up, new ProxyProperties.Routing(8000, Duration.ofMinutes(1)), "",
                java.util.List.of("qwen3-14b", "qwen3.6-35b", "qwen3.8-27b"));
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
    void completionsForcesConfiguredTemperatureOverClientValue() throws Exception {
        ProxyProperties props = props("env-key", "env-model", 0.0);
        ProxyService service = new ProxyService(props, new LlmConfigState(), mapper);

        service.completions("{\"messages\":[],\"temperature\":1.5}", "openrouter", null);

        assertThat(lastTemperature.get()).isEqualTo("0.0");
    }

    @Test
    void completionsLeavesTemperatureUntouchedWhenNotConfigured() throws Exception {
        ProxyProperties props = props("env-key", "env-model", null);
        ProxyService service = new ProxyService(props, new LlmConfigState(), mapper);

        service.completions("{\"messages\":[],\"temperature\":1.5}", "openrouter", null);

        assertThat(lastTemperature.get()).isEqualTo("1.5");
    }

    /** Same policy as {@link #completionsForcesConfiguredTemperatureOverClientValue}, but for a
     *  caller that never set the field at all (e.g. LazyInvest's Phase 2, which sends its own
     *  0.7) — the forced value must still reach the backend, not just override an explicit one. */
    @Test
    void completionsForcesConfiguredTemperatureWhenClientOmitsIt() throws Exception {
        ProxyProperties props = props("env-key", "env-model", 0.0);
        ProxyService service = new ProxyService(props, new LlmConfigState(), mapper);

        service.completions("{\"messages\":[]}", "openrouter", null);

        assertThat(lastTemperature.get()).isEqualTo("0.0");
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

    @Test
    void completionsPassesThroughDraftTimingsUnmodified() throws Exception {
        upstream.removeContext("/v1/chat/completions");
        String upstreamBody = "{\"choices\":[],\"timings\":{\"draft_n\":48,\"draft_n_accepted\":30}}";
        upstream.createContext("/v1/chat/completions", exchange -> {
            byte[] response = upstreamBody.getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        ProxyProperties props = props("env-key", "env-model");
        ProxyService service = new ProxyService(props, new LlmConfigState(), mapper);

        var response = service.completions("{\"messages\":[]}", "local", null);

        assertThat(response.getBody()).isEqualTo(upstreamBody);
    }

    @Test
    void chatModelsFiltersOutEmbeddingAndRerankModels() throws Exception {
        ProxyProperties props = props("env-key", "env-model");
        ProxyService service = new ProxyService(props, new LlmConfigState(), mapper);

        var response = service.chatModels();

        var ids = mapper.readTree(response.getBody()).get("data");
        assertThat(ids).hasSize(2);
        assertThat(ids).extracting(n -> n.get("id").asText())
                .containsExactlyInAnyOrder("qwen3-14b", "qwen3.6-35b");
    }

    // ── mergeGuardrailSystemPrompt: zero coverage before this (SPEC-chat-prompt-quality-and-
    // fidelity.md, A2 measurement flagged the gap). The marker below is guardrails.md's own
    // heading, stable across edits to the body text.
    private static final String GUARDRAIL_MARKER = "# Guardrails de segurança";

    @Test
    void completionsPrependsGuardrailToExistingTextualSystemMessage() throws Exception {
        ProxyProperties props = props("env-key", "env-model");
        ProxyService service = new ProxyService(props, new LlmConfigState(), mapper);

        service.completions(
                "{\"messages\":[{\"role\":\"system\",\"content\":\"Você é um analista financeiro.\"}]}",
                "local", null);

        assertThat(lastMessages.get()).hasSize(1);
        String content = lastMessages.get().get(0).path("content").asText();
        assertThat(content).startsWith(GUARDRAIL_MARKER);
        assertThat(content).contains("Você é um analista financeiro.");
        assertThat(content.indexOf(GUARDRAIL_MARKER)).isLessThan(content.indexOf("Você é um analista financeiro."));
    }

    @Test
    void completionsInsertsGuardrailAsNewSystemMessageWhenNoneExists() throws Exception {
        ProxyProperties props = props("env-key", "env-model");
        ProxyService service = new ProxyService(props, new LlmConfigState(), mapper);

        service.completions(
                "{\"messages\":[{\"role\":\"user\",\"content\":\"qual o ROE da VALE3?\"}]}",
                "local", null);

        assertThat(lastMessages.get()).hasSize(2);
        assertThat(lastMessages.get().get(0).path("role").asText()).isEqualTo("system");
        assertThat(lastMessages.get().get(0).path("content").asText()).startsWith(GUARDRAIL_MARKER);
        assertThat(lastMessages.get().get(1).path("role").asText()).isEqualTo("user");
    }

    /** A non-textual first-message content (multimodal content blocks) would read back as "" via
     *  {@code asText()} and silently wipe the caller's prompt if merged in-place — must insert a
     *  separate system message instead (see the javadoc on {@code mergeGuardrailSystemPrompt}). */
    @Test
    void completionsInsertsSeparateMessageWhenFirstSystemMessageIsMultimodal() throws Exception {
        ProxyProperties props = props("env-key", "env-model");
        ProxyService service = new ProxyService(props, new LlmConfigState(), mapper);

        service.completions(
                "{\"messages\":[{\"role\":\"system\",\"content\":[{\"type\":\"text\",\"text\":\"instrução\"}]}]}",
                "local", null);

        assertThat(lastMessages.get()).hasSize(2);
        assertThat(lastMessages.get().get(0).path("role").asText()).isEqualTo("system");
        assertThat(lastMessages.get().get(0).path("content").asText()).startsWith(GUARDRAIL_MARKER);
        // The caller's original multimodal system message is preserved untouched, one slot later.
        assertThat(lastMessages.get().get(1).path("content").isArray()).isTrue();
    }

    @Test
    void completionsSkipsGuardrailForResponseFormatRequests() throws Exception {
        ProxyProperties props = props("env-key", "env-model");
        ProxyService service = new ProxyService(props, new LlmConfigState(), mapper);

        service.completions(
                "{\"messages\":[{\"role\":\"user\",\"content\":\"extraia os campos\"}],"
                        + "\"response_format\":{\"type\":\"json_object\"}}",
                "local", null);

        assertThat(lastMessages.get()).hasSize(1);
        assertThat(lastMessages.get().get(0).path("role").asText()).isEqualTo("user");
    }
}
