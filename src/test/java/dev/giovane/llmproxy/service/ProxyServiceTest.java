package dev.giovane.llmproxy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import dev.giovane.llmproxy.config.LlmConfigState;
import dev.giovane.llmproxy.config.ProxyProperties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
    private final AtomicReference<com.fasterxml.jackson.databind.JsonNode> lastProvider = new AtomicReference<>();
    private final AtomicReference<String> lastToolChoice = new AtomicReference<>();
    private final AtomicReference<com.fasterxml.jackson.databind.JsonNode> lastBody = new AtomicReference<>();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void startUpstream() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.createContext("/v1/chat/completions", exchange -> {
            lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastPath.set(exchange.getRequestURI().getPath());
            var body = mapper.readTree(exchange.getRequestBody());
            lastBody.set(body);
            lastModel.set(body.path("model").asText(null));
            lastTemperature.set(body.has("temperature") ? body.path("temperature").asText() : null);
            lastProvider.set(body.get("provider"));
            lastToolChoice.set(body.has("tool_choice") ? body.path("tool_choice").asText() : null);
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
            // supported_parameters em 2 dos 4 (openai/gpt-4o-mini, deepseek/deepseek-chat) —
            // usado por openrouterModelsFiltersToStructuredOutputsCapableModels abaixo; os
            // outros dois seguem sem o campo, cobrindo tanto "ausente" quanto "presente sem o
            // valor certo".
            byte[] response = ("{\"data\":[{\"id\":\"bge-m3\"},{\"id\":\"qwen3-14b\"},"
                    + "{\"id\":\"bge-reranker-v2-m3\"},{\"id\":\"qwen3.6-35b\"},"
                    + "{\"id\":\"openai/gpt-4o-mini\",\"supported_parameters\":"
                    + "[\"temperature\",\"structured_outputs\"]},"
                    + "{\"id\":\"deepseek/deepseek-chat\",\"supported_parameters\":"
                    + "[\"structured_outputs\",\"tools\"]},"
                    + "{\"id\":\"meta/llama-3-8b\",\"supported_parameters\":[\"temperature\"]}]}").getBytes();
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
                java.util.List.of("qwen3-14b", "qwen3.6-35b", "qwen3.8-27b"), java.util.List.of("openai/gpt-6"));
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

    /** gpt-6-luna recusa temperature: com tools o proxy exige require_parameters, então injetá-la dava 404. */
    @Test
    void completionsDropsTemperatureForOpenRouterModelsThatRejectIt() throws Exception {
        ProxyProperties props = props("env-key", "env-model", 0.0);
        ProxyService service = new ProxyService(props, new LlmConfigState(), mapper);

        service.completions("{\"model\":\"openai/gpt-6-luna\",\"messages\":[],\"temperature\":0.3,"
                + "\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"x\"}}]}", "openrouter", null);

        assertThat(lastTemperature.get()).isNull();
    }

    @Test
    void otherOpenRouterModelsStillGetTheForcedTemperature() throws Exception {
        ProxyProperties props = props("env-key", "env-model", 0.0);
        ProxyService service = new ProxyService(props, new LlmConfigState(), mapper);

        service.completions("{\"model\":\"openai/gpt-4o-mini\",\"messages\":[]}", "openrouter", null);

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
    void plainOpenRouterCompletionDoesNotRequireEveryEndpointParameter() throws Exception {
        ProxyService service = new ProxyService(props("env-key", "env-model", 0.0),
                new LlmConfigState(), mapper);

        service.completions("{\"messages\":[],\"reasoning\":{\"max_tokens\":512}}",
                "openrouter", null);

        assertThat(lastProvider.get()).isNull();
    }

    @Test
    void toolCallingStillPinsOpenRouterToCompatibleEndpoint() throws Exception {
        ProxyService service = new ProxyService(props("env-key", "env-model", 0.0),
                new LlmConfigState(), mapper);

        service.completions("{\"messages\":[],\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"lookup\"}}]}",
                "openrouter", null);

        assertThat(lastProvider.get().path("allow_fallbacks").asBoolean()).isFalse();
        assertThat(lastProvider.get().path("require_parameters").asBoolean()).isTrue();
    }

    @Test
    void jsonSchemaStillPinsOpenRouterToCompatibleEndpoint() throws Exception {
        ProxyService service = new ProxyService(props("env-key", "env-model", 0.0),
                new LlmConfigState(), mapper);

        service.completions("{\"messages\":[],\"response_format\":{\"type\":\"json_schema\"}}",
                "openrouter", null);

        assertThat(lastProvider.get().path("allow_fallbacks").asBoolean()).isFalse();
        assertThat(lastProvider.get().path("require_parameters").asBoolean()).isTrue();
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

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        response.getBody().writeTo(out);
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo(upstreamBody);
    }

    /** Regression for the bug fixed in this session: stream:true used to go through forward()
     *  (toEntity(String.class)), which fully buffers the upstream body before returning — for an
     *  SSE caller that means zero bytes reach the client until generation finishes entirely.
     *  completions() must route stream:true through forwardStreaming() instead, whose
     *  StreamingResponseBody copies bytes through unmodified. */
    @Test
    void completionsWithStreamTrueReturnsStreamingResponseBodyThatCopiesBytesAsIs() throws Exception {
        String sseBody = "data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"b\"}}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}\n\n"
                + "data: [DONE]\n\n";
        upstream.removeContext("/v1/chat/completions");
        upstream.createContext("/v1/chat/completions", exchange -> {
            byte[] response = sseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        ProxyProperties props = props("env-key", "env-model");
        ProxyService service = new ProxyService(props, new LlmConfigState(), mapper);

        ResponseEntity<StreamingResponseBody> response =
                service.completions("{\"messages\":[],\"stream\":true}", "local", null);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        response.getBody().writeTo(out);
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo(sseBody);
    }

    /** Regression: onStatus(s -> false, ...) never matched, so RestClient's default handler ran
     *  anyway and threw on every non-2xx — a 429 from the upstream reached the caller as an
     *  opaque Spring 500 instead of the real 429 + error body. */
    @Test
    void completionsRelaysUpstreamErrorStatusInsteadOfThrowing() throws Exception {
        String errorBody = "{\"error\":{\"message\":\"rate limited\"}}";
        upstream.removeContext("/v1/chat/completions");
        upstream.createContext("/v1/chat/completions", exchange -> {
            byte[] response = errorBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(429, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        ProxyProperties props = props("env-key", "env-model");
        ProxyService service = new ProxyService(props, new LlmConfigState(), mapper);

        ResponseEntity<StreamingResponseBody> response = service.completions("{\"messages\":[]}", "local", null);

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        response.getBody().writeTo(out);
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo(errorBody);
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

    @Test
    void openrouterModelsFiltersToStructuredOutputsCapableModels() throws Exception {
        ProxyProperties props = props("env-key", "env-model");
        ProxyService service = new ProxyService(props, new LlmConfigState(), mapper);

        var response = service.openrouterModels();

        var ids = mapper.readTree(response.getBody()).get("data");
        assertThat(ids).extracting(n -> n.get("id").asText())
                .containsExactlyInAnyOrder("openai/gpt-4o-mini", "deepseek/deepseek-chat");
    }

    @Test
    void openrouterModelsCachesAcrossCalls() throws Exception {
        ProxyProperties props = props("env-key", "env-model");
        ProxyService service = new ProxyService(props, new LlmConfigState(), mapper);

        service.openrouterModels();
        upstream.stop(0);  // upstream indisponível na 2ª chamada — só passa se veio do cache
        var second = service.openrouterModels();

        assertThat(second.getStatusCode().is2xxSuccessful()).isTrue();
        var ids = mapper.readTree(second.getBody()).get("data");
        assertThat(ids).extracting(n -> n.get("id").asText())
                .containsExactlyInAnyOrder("openai/gpt-4o-mini", "deepseek/deepseek-chat");
    }

    private void upstreamReturns(String path, int status) {
        try {
            upstream.removeContext(path);
        } catch (IllegalArgumentException noSuchContext) {
            // /health is not registered by default
        }
        upstream.createContext(path, exchange -> {
            byte[] response = "{\"error\":{\"message\":\"down\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
    }

    /** C3 (ADR-012): health/models/openrouterModels relayed non-2xx as an opaque 500. */
    @Test
    void modelsRelaysUpstreamErrorStatusInsteadOfThrowing() {
        upstreamReturns("/v1/models", 503);
        ProxyService service = new ProxyService(props("env-key", "env-model"), new LlmConfigState(), mapper);

        assertThat(service.models().getStatusCode().value()).isEqualTo(503);
        assertThat(service.chatModels().getStatusCode().value()).isEqualTo(503);
        assertThat(service.openrouterModels().getStatusCode().value()).isEqualTo(503);
    }

    @Test
    void healthRelaysUpstreamErrorStatusInsteadOfThrowing() {
        upstreamReturns("/health", 503);
        ProxyService service = new ProxyService(props("env-key", "env-model"), new LlmConfigState(), mapper);

        assertThat(service.health().getStatusCode().value()).isEqualTo(503);
    }

    /** I2: an upstream error must not be cached — the next call retries. */
    @Test
    void openrouterModelsDoesNotCacheUpstreamError() throws Exception {
        upstreamReturns("/v1/models", 429);
        ProxyService service = new ProxyService(props("env-key", "env-model"), new LlmConfigState(), mapper);
        assertThat(service.openrouterModels().getStatusCode().value()).isEqualTo(429);

        upstream.removeContext("/v1/models");
        upstream.createContext("/v1/models", exchange -> {
            byte[] response = "{\"data\":[{\"id\":\"a/b\",\"supported_parameters\":[\"structured_outputs\"]}]}".getBytes();
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        var second = service.openrouterModels();
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(mapper.readTree(second.getBody()).get("data")).hasSize(1);
    }

    /** I2: a refresh in flight must not block callers when a (stale) catalog exists. */
    @Test
    void openrouterModelsServesCachedCatalogWhileAnotherThreadRefreshes() throws Exception {
        ProxyService service = new ProxyService(props("env-key", "env-model"), new LlmConfigState(), mapper);
        service.openrouterModels();
        var lockField = ProxyService.class.getDeclaredField("openrouterModelsRefresh");
        lockField.setAccessible(true);
        var lock = (java.util.concurrent.locks.ReentrantLock) lockField.get(service);
        var cacheField = ProxyService.class.getDeclaredField("openrouterModelsCache");
        cacheField.setAccessible(true);
        var cache = (AtomicReference<?>) cacheField.get(service);
        Object fresh = cache.get();
        var ctor = fresh.getClass().getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object stale = ctor.newInstance(fresh.getClass().getRecordComponents()[0].getAccessor().invoke(fresh),
                java.time.Instant.EPOCH);  // vencido
        AtomicReference.class.getMethod("set", Object.class).invoke(cache, stale);
        upstream.stop(0);

        Thread holder = new Thread(lock::lock);  // simula outra thread presa na chamada HTTP
        holder.start();
        holder.join();
        var response = java.util.concurrent.CompletableFuture.supplyAsync(service::openrouterModels)
                .get(5, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(mapper.readTree(response.getBody()).get("data")).hasSize(2);
    }

    /** I1: a body that is not a JSON object is the caller's error. */
    @Test
    void nonObjectRequestBodyIsRejectedAsInvalidRequest() {
        ProxyService service = new ProxyService(props("env-key", "env-model"), new LlmConfigState(), mapper);

        for (String body : java.util.List.of("[]", "\"x\"", "{", "null", "")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.completions(body, "local", null))
                    .as(body).isInstanceOf(InvalidRequestException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.embeddings(body))
                    .as(body).isInstanceOf(InvalidRequestException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.rerank(body))
                    .as(body).isInstanceOf(InvalidRequestException.class);
        }
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
    void completionsForwardsToolChoiceUnchanged() throws Exception {
        ProxyProperties props = props("env-key", "env-model");
        ProxyService service = new ProxyService(props, new LlmConfigState(), mapper);

        service.completions(
                "{\"messages\":[{\"role\":\"user\",\"content\":\"busque ROE\"}],"
                        + "\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"get_financials\"}}],"
                        + "\"tool_choice\":\"auto\"}",
                "openrouter", null);

        assertThat(lastToolChoice.get()).isEqualTo("auto");
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

    private ProxyProperties openrouterProps(Boolean usageAccounting) {
        ProxyProperties.Upstream up = new ProxyProperties.Upstream(baseUrl(), "k", "anthropic/claude-haiku-5.5", 0.0,
                usageAccounting, null);
        return new ProxyProperties(up, up, up, up, new ProxyProperties.Routing(8000, Duration.ofMinutes(1)), "",
                java.util.List.of("qwen3-14b"), java.util.List.of());
    }

    @Test
    void openRouterRequestAsksForUsageAccountingAndStreamUsage() throws Exception {
        ProxyService service = new ProxyService(openrouterProps(null), new LlmConfigState(), mapper);

        service.completions("{\"messages\":[],\"stream\":true}", "openrouter", null)
                .getBody().writeTo(new ByteArrayOutputStream());

        assertThat(lastBody.get().path("usage").path("include").asBoolean()).isTrue();
        assertThat(lastBody.get().path("stream_options").path("include_usage").asBoolean()).isTrue();
    }

    @Test
    void usageAccountingKeepsCallerValuesAndSkipsStreamOptionsWithoutStream() throws Exception {
        ProxyService service = new ProxyService(openrouterProps(null), new LlmConfigState(), mapper);

        service.completions("{\"messages\":[],\"usage\":{\"include\":false}}", "openrouter", null);

        assertThat(lastBody.get().path("usage").path("include").asBoolean()).isFalse();
        assertThat(lastBody.get().has("stream_options")).isFalse();
    }

    @Test
    void usageAccountingIsNotInjectedOnLocalRouteOrWhenToggledOff() throws Exception {
        new ProxyService(props("k", "m"), new LlmConfigState(), mapper)
                .completions("{\"messages\":[]}", "local", null);
        assertThat(lastBody.get().has("usage")).isFalse();

        new ProxyService(openrouterProps(false), new LlmConfigState(), mapper)
                .completions("{\"messages\":[]}", "openrouter", null);
        assertThat(lastBody.get().has("usage")).isFalse();
    }

    @Test
    void streamLogReadsNestedUsageFromLastEventWithUsage() throws Exception {
        String sse = new String(getClass().getResourceAsStream("/fixtures/openrouter-usage-stream.sse")
                .readAllBytes(), StandardCharsets.UTF_8);
        upstream.removeContext("/v1/chat/completions");
        upstream.createContext("/v1/chat/completions", exchange -> {
            byte[] response = sse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ProxyService.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            new ProxyService(openrouterProps(null), new LlmConfigState(), mapper)
                    .completions("{\"messages\":[],\"stream\":true}", "openrouter", null)
                    .getBody().writeTo(new ByteArrayOutputStream());
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list.stream().map(e -> e.getFormattedMessage()))
                .anyMatch(m -> m.contains("promptTokens=1154") && m.contains("completionTokens=4")
                        && m.contains("cachedTokens=0") && m.contains("cost=0.0001174"));
    }

    @Test
    void nonStreamLogShowsNotAvailableWhenUpstreamOmitsCachedAndCost() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ProxyService.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            new ProxyService(props("k", "m"), new LlmConfigState(), mapper)
                    .completions("{\"messages\":[]}", "local", null);
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list.stream().map(e -> e.getFormattedMessage()))
                .anyMatch(m -> m.contains("cachedTokens=n/d") && m.contains("cost=n/d"));
    }

    /** SPEC-chat-backlog C.C2: backend fora no caminho stream também gera o alarme "backend indisponível". */
    @Test
    void streamWithBackendDownLogsBackendUnavailable() throws Exception {
        String deadUrl = baseUrl();
        upstream.stop(0);
        ProxyProperties.Upstream up = new ProxyProperties.Upstream(deadUrl, "k", "m", null);
        ProxyService service = new ProxyService(new ProxyProperties(up, up, up, up,
                new ProxyProperties.Routing(8000, Duration.ofMinutes(1)), "", java.util.List.of(), java.util.List.of()),
                new LlmConfigState(), mapper);
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ProxyService.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    service.completions("{\"messages\":[],\"stream\":true}", "local", null))
                    .isInstanceOf(org.springframework.web.client.ResourceAccessException.class);
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list.stream().map(e -> e.getFormattedMessage()))
                .anyMatch(m -> m.startsWith("backend indisponível"));
    }

    /** SPEC-chat-backlog C.I3/C.M4: só a linha de usage é lida, com multibyte íntegro, e o provider do stream vai ao log. */
    @Test
    void streamSummaryReadsTheUsageLineAndTheOpenRouterProvider() throws Exception {
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"ação\"}}]}\n\n"
                + "data: {\"provider\":\"Anthropic\",\"choices\":[],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":2}}\n\n"
                + "data: [DONE]\n\n";
        upstream.removeContext("/v1/chat/completions");
        upstream.createContext("/v1/chat/completions", exchange -> {
            byte[] response = sse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ProxyService.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            new ProxyService(openrouterProps(null), new LlmConfigState(), mapper)
                    .completions("{\"messages\":[],\"stream\":true}", "openrouter", null)
                    .getBody().writeTo(new ByteArrayOutputStream());
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list.stream().map(e -> e.getFormattedMessage()))
                .anyMatch(m -> m.contains("promptTokens=7") && m.contains("completionTokens=2"))
                .anyMatch(m -> m.contains("provider=Anthropic") && m.contains("(stream)"));
    }

    /** SPEC-chat-backlog C.I4: tools e conteúdo em partes entram na estimativa que decide o roteamento. */
    @Test
    void estimateTokensCountsToolsAndMultipartContent() throws Exception {
        String bigDescription = "x".repeat(40_000);
        var json = mapper.readTree("{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\""
                + "y".repeat(400) + "\"}]}],\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"t\","
                + "\"description\":\"" + bigDescription + "\"}}]}");

        int tokens = ProxyService.estimateTokens(json);

        assertThat(tokens).isGreaterThan(8000); // cruza o speed-token-threshold padrão
        assertThat(ProxyService.estimateTokens(mapper.readTree("{\"messages\":[{\"content\":[{\"type\":\"text\","
                + "\"text\":\"abcdefgh\"}]}]}"))).isEqualTo(2);
    }

    private ProxyService cacheService(Boolean promptCache) {
        ProxyProperties.Upstream up = new ProxyProperties.Upstream(baseUrl(), "k", "anthropic/claude-haiku-5.5", 0.0,
                null, promptCache);
        return new ProxyService(new ProxyProperties(up, up, up, up,
                new ProxyProperties.Routing(8000, Duration.ofMinutes(1)), "", java.util.List.of(),
                java.util.List.of()), new LlmConfigState(), mapper);
    }

    private static final String CHAT_WITH_TOOL_TURN = "{\"model\":\"anthropic/claude-haiku-5.5\",\"messages\":["
            + "{\"role\":\"system\",\"content\":\"regras\"},"
            + "{\"role\":\"user\",\"content\":\"antiga\"},{\"role\":\"assistant\",\"content\":\"ok\"},"
            + "{\"role\":\"user\",\"content\":\"pergunta\"},"
            + "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"1\",\"type\":\"function\","
            + "\"function\":{\"name\":\"f\",\"arguments\":\"{}\"}}]},"
            + "{\"role\":\"tool\",\"tool_call_id\":\"1\",\"content\":\"dado\"}]}";

    @Test
    void promptCacheMarksSystemAndLastUserOnlyForAnthropicOnOpenRouter() throws Exception {
        cacheService(null).completions(CHAT_WITH_TOOL_TURN, "openrouter", null);

        var msgs = lastBody.get().path("messages");
        // guardrail é mesclado no system existente: continua sendo a mensagem 0
        var system = msgs.get(0).path("content");
        assertThat(system.isArray()).isTrue();
        assertThat(system.get(0).path("cache_control").path("type").asText()).isEqualTo("ephemeral");
        assertThat(system.get(0).path("text").asText()).endsWith("regras");
        assertThat(msgs.get(1).path("content").isTextual()).isTrue(); // user antigo intacto
        assertThat(msgs.get(3).path("content").get(0).path("cache_control").path("type").asText())
                .isEqualTo("ephemeral");
        assertThat(msgs.get(3).path("content").get(0).path("text").asText()).isEqualTo("pergunta");
        assertThat(msgs.get(4).has("cache_control")).isFalse();
        assertThat(msgs.get(5).path("content").asText()).isEqualTo("dado"); // tool intacta
    }

    @Test
    void promptCacheSkipsNonAnthropicModelsLocalRouteAndToggleOff() throws Exception {
        cacheService(null).completions(CHAT_WITH_TOOL_TURN.replace("anthropic/claude-haiku-5.5", "openai/gpt-4o-mini"),
                "openrouter", null);
        assertThat(lastBody.get().toString()).doesNotContain("cache_control");

        cacheService(null).completions(CHAT_WITH_TOOL_TURN, "local", null);
        assertThat(lastBody.get().toString()).doesNotContain("cache_control");

        cacheService(false).completions(CHAT_WITH_TOOL_TURN, "openrouter", null);
        assertThat(lastBody.get().toString()).doesNotContain("cache_control");
    }

    @Test
    void promptCachePreservesCallerCacheControl() throws Exception {
        String body = "{\"model\":\"anthropic/claude-haiku-5.5\",\"messages\":["
                + "{\"role\":\"system\",\"content\":\"s\"},"
                + "{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"q\","
                + "\"cache_control\":{\"type\":\"ephemeral\",\"ttl\":\"1h\"}}]}]}";

        cacheService(null).completions(body, "openrouter", null);

        var msgs = lastBody.get().path("messages");
        assertThat(msgs.get(0).path("content").isTextual()).isTrue();
        assertThat(msgs.get(1).path("content").get(0).path("cache_control").path("ttl").asText()).isEqualTo("1h");
    }

    @Test
    void promptCacheMarksLastPartOfExistingContentArray() throws Exception {
        String body = "{\"model\":\"anthropic/claude-haiku-5.5\",\"messages\":["
                + "{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"a\"},"
                + "{\"type\":\"text\",\"text\":\"b\"}]}]}";

        cacheService(null).completions(body, "openrouter", null);

        var parts = lastBody.get().path("messages").get(1).path("content"); // 0 = guardrail inserido
        assertThat(parts.get(0).has("cache_control")).isFalse();
        assertThat(parts.get(1).path("cache_control").path("type").asText()).isEqualTo("ephemeral");
    }
}
