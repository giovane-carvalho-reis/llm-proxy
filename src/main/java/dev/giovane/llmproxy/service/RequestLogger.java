package dev.giovane.llmproxy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.giovane.llmproxy.router.Router;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** One-line request summaries and the throttled "backend indisponível" alarm. Best-effort: never fails a response. */
@Component
class RequestLogger {

    private static final Logger log = LoggerFactory.getLogger(RequestLogger.class);

    // SPEC-servidor-de-inferencia-local §6: sem isto, um backend caído (llama-swap fora do ar)
    // só aparecia como stack trace bruto do java.net.ConnectException — nenhuma linha dizia
    // "o backend está ausente", então a regra de alarme do Loki (`|= "backend indisponível"`)
    // nunca disparava. Throttle de 1/min por baseUrl: evita 1 linha ERROR por request numa
    // fila de retry (cvm-pdf-processor já loga "embed retryable attempt=N/6" por cima disso).
    private static final Duration BACKEND_DOWN_LOG_THROTTLE = Duration.ofMinutes(1);
    private final Map<String, Instant> lastBackendDownLog = new ConcurrentHashMap<>();

    void openRouterProvider(ObjectNode request, JsonNode resp) {
        if (resp.isMissingNode()) {
            return;
        }
        boolean hasToolCalls = resp.path("choices").path(0).path("message").has("tool_calls");
        log.info("openrouter model={} provider={} toolCalls={}",
                request.path("model").asText("?"), resp.path("provider").asText("?"), hasToolCalls);
    }

    /**
     * llama.cpp's --spec-type draft-mtp puts draft_n/draft_n_accepted inside "timings", but
     * langchain4j-open-ai (the only caller) parses only the OpenAI-standard response fields and
     * silently drops "timings" — so the acceptance rate never reaches any app-level log unless we
     * read it here, before it's discarded.
     */
    void draftAcceptance(ObjectNode request, JsonNode resp) {
        JsonNode timings = resp.path("timings");
        int draftN = timings.path("draft_n").asInt(0);
        if (draftN <= 0) {
            return;
        }
        int accepted = timings.path("draft_n_accepted").asInt(0);
        log.info("mtp model={} draft={}/{} accepted={}%",
                request.path("model").asText("?"), accepted, draftN, Math.round(100.0 * accepted / draftN));
    }

    /** N3: 1 linha INFO por request de embeddings/rerank (sem conteúdo). */
    void embedSummary(String kind, ObjectNode request, int status, long latencyMs, int inputs) {
        log.info("embed kind={} model={} status={} latencyMs={} inputs={}", kind, request.path("model").asText("?"),
                status, latencyMs, inputs);
    }

    /**
     * One line per non-stream chat request — route/model/status/latency/tokens. Usage fields come
     * from the upstream's own OpenAI-shaped "usage" object; missing on some llama.cpp builds, so
     * "n/d" there means "not reported", never "zero tokens" (N6).
     */
    void requestSummary(String target, ObjectNode request, JsonNode resp, int status, long latencyMs) {
        JsonNode usage = resp.path("usage");
        // "timings" é o mesmo campo lido por draftAcceptance — builds sem --spec-type draft-mtp
        // (ou sem suporte a timings) não têm os nós; ficam "n/d", nunca "0".
        JsonNode timings = resp.path("timings");
        String promptMs = timings.has("prompt_ms") ? String.valueOf(timings.path("prompt_ms").asDouble()) : "n/d";
        String predictedMs = timings.has("predicted_ms") ? String.valueOf(timings.path("predicted_ms").asDouble()) : "n/d";
        log.info("chat route={} model={} status={} latencyMs={} promptTokens={} completionTokens={} cachedTokens={} cost={} promptMs={} predictedMs={}",
                target, request.path("model").asText("?"), status, latencyMs,
                tokens(usage, "prompt_tokens"), tokens(usage, "completion_tokens"),
                cachedTokens(usage), cost(usage), promptMs, predictedMs);
    }

    /** Stream summary; {@code chunk} é o último evento {@code data:} cujo {@code usage} é objeto (ou null). */
    void streamSummary(String target, ObjectNode request, JsonNode chunk, int status, long latencyMs, String outcome) {
        JsonNode last = chunk == null ? MissingNode.getInstance() : chunk;
        JsonNode usage = last.path("usage");
        if (Router.OPENROUTER.equals(target) && last.has("provider")) {
            // SPEC-chat-backlog C.M4: o ramo stream retorna antes de openRouterProvider.
            log.info("openrouter model={} provider={} (stream)", request.path("model").asText("?"),
                    last.path("provider").asText("?"));
        }
        log.info("chat route={} model={} status={} latencyMs={} promptTokens={} completionTokens={} cachedTokens={} cost={} outcome={} (stream)",
                target, request.path("model").asText("?"), status, latencyMs,
                tokens(usage, "prompt_tokens"), tokens(usage, "completion_tokens"),
                cachedTokens(usage), cost(usage), outcome);
    }

    private static String tokens(JsonNode usage, String field) {
        JsonNode n = usage.path(field);
        return n.isNumber() ? String.valueOf(n.asLong()) : "n/d";
    }

    private static String cachedTokens(JsonNode usage) {
        JsonNode n = usage.path("prompt_tokens_details").path("cached_tokens");
        return n.isNumber() ? String.valueOf(n.asLong()) : "n/d";
    }

    private static String cost(JsonNode usage) {
        JsonNode n = usage.path("cost");
        return n.isNumber() ? n.decimalValue().toPlainString() : "n/d";
    }

    void backendDownThrottled(String baseUrl, String path, Exception cause) {
        Instant now = Instant.now();
        Instant last = lastBackendDownLog.get(baseUrl);
        if (last != null && now.isBefore(last.plus(BACKEND_DOWN_LOG_THROTTLE))) {
            return;
        }
        lastBackendDownLog.put(baseUrl, now);
        log.error("backend indisponível | baseUrl={} path={} causa={}", baseUrl, path, cause.getMessage());
    }
}
