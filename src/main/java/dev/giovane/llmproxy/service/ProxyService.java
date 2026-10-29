package dev.giovane.llmproxy.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.giovane.llmproxy.api.InvalidRequestException;
import dev.giovane.llmproxy.config.LlmConfigState;
import dev.giovane.llmproxy.config.ProxyProperties;
import dev.giovane.llmproxy.router.Router;
import dev.giovane.llmproxy.upstream.UpstreamFailure;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * OpenAI-compatible reverse proxy logic. Forwards the request body verbatim (Jackson
 * round-trip only, so llama.cpp-only fields like response_format/cache_prompt/
 * enable_thinking survive) to whichever upstream {@link Router} picks: parse, guardrail, route, forward.
 */
@Service
public class ProxyService {

    private final ProxyProperties props;
    private final LlmConfigState configState;
    private final ObjectMapper mapper;
    private final UpstreamHttp http;
    private final OpenRouterPayloadCustomizer customizer;
    private final RequestLogger requestLog;
    private final SseRelay sseRelay;
    private final String guardrailPrompt;

    public ProxyService(ProxyProperties props, LlmConfigState configState, ObjectMapper mapper, UpstreamHttp http,
            OpenRouterPayloadCustomizer customizer, RequestLogger requestLog, SseRelay sseRelay) {
        this.props = props;
        this.configState = configState;
        this.mapper = mapper;
        this.http = http;
        this.customizer = customizer;
        this.requestLog = requestLog;
        this.sseRelay = sseRelay;
        this.guardrailPrompt = loadGuardrailPrompt();
    }

    // Declared as ResponseEntity<StreamingResponseBody> (not ResponseEntity<?>) all the way
    // through to the controller — Spring's StreamingResponseBodyReturnValueHandler.
    // supportsReturnType resolves the *declared* generic type via
    // ResolvableType.forMethodParameter(...).getGeneric(0), not the runtime instance. A wildcard
    // there resolves to null, the handler doesn't match, and Spring falls back to
    // HttpMessageConverters — which have no converter for a raw lambda, producing
    // "HttpMessageNotWritableException: No converter for [...Lambda] with preset Content-Type
    // 'text/event-stream'" (caught live against this exact code before this comment existed).
    // The non-streaming branch below wraps its normal buffered String response in a
    // StreamingResponseBody that just writes those same bytes once, so both branches share one
    // concrete, resolvable return type.
    public ResponseEntity<StreamingResponseBody> completions(String body, String route, String priority) throws Exception {
        ObjectNode json = parseRequestObject(body);

        // Before routing: the guardrail adds real tokens to the prompt, and the auto-route
        // threshold is a token count. Estimating first would route on a size the upstream
        // never sees.
        mergeGuardrailSystemPrompt(json);

        boolean speed = "speed".equalsIgnoreCase(priority);
        String target = Router.resolve(route, speed, estimateTokens(json), props.routing().speedTokenThreshold());

        ProxyProperties.Upstream up = configState.effective(target,
                Router.OPENROUTER.equals(target) ? props.openrouter() : props.llamaCpp());

        // Fill in the backend's default model when the caller didn't pin one — the whole point
        // of "auto" is that the caller doesn't know which backend (and thus which model id) runs.
        fillDefaultModel(json, up);
        customizer.customize(json, target, up);
        String payload = mapper.writeValueAsString(json);

        // stream:true bypasses forward() entirely — that method fully buffers the upstream body
        // into a String before returning, which for a streaming caller (LazyInvest's SSE chat)
        // means zero bytes reach the client until the ENTIRE generation finishes. That silently
        // turned every "final answer" stream into a single blocking wait as long as the model's
        // full generation time, long enough to trip idle-timeout watchdogs downstream that assume
        // tokens arrive incrementally. See SseRelay for the real passthrough.
        if (json.path("stream").asBoolean(false)) {
            return sseRelay.forward(up, "/v1/chat/completions", payload, target, json);
        }

        long startedAt = System.currentTimeMillis();
        ResponseEntity<String> response = forward(up, "/v1/chat/completions", payload);
        long latencyMs = System.currentTimeMillis() - startedAt;

        // Single parse of the response body, shared by every log line below.
        JsonNode resp = parseResponseBody(response);
        if (Router.OPENROUTER.equals(target)) {
            requestLog.openRouterProvider(json, resp);
        } else {
            requestLog.draftAcceptance(json, resp);
        }
        requestLog.requestSummary(target, json, resp, response.getStatusCode().value(), latencyMs);

        StreamingResponseBody wrapped = out -> {
            if (response.getBody() != null) {
                out.write(response.getBody().getBytes(StandardCharsets.UTF_8));
            }
        };
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).body(wrapped);
    }
    /** Embeddings passthrough — always the local bge-m3 llama-server; no routing to decide. */
    public ResponseEntity<String> embeddings(String body) throws Exception {
        ObjectNode json = parseRequestObject(body);
        ProxyProperties.Upstream up = props.embeddings();
        fillDefaultModel(json, up);
        return forwardLogged("embeddings", up, "/v1/embeddings", json, "input");
    }

    /** Rerank passthrough — always the local bge-reranker-v2-m3 llama-server; no routing to decide. */
    public ResponseEntity<String> rerank(String body) throws Exception {
        ObjectNode json = parseRequestObject(body);
        ProxyProperties.Upstream up = props.rerank();
        fillDefaultModel(json, up);
        return forwardLogged("rerank", up, "/v1/rerank", json, "documents");
    }

    /** N3: 1 linha INFO por request de embeddings/rerank (sem conteúdo); em falha de conexão só o alarme de backend-down. */
    private ResponseEntity<String> forwardLogged(String kind, ProxyProperties.Upstream up, String path,
            ObjectNode json, String inputsField) throws JsonProcessingException {
        long startedAt = System.currentTimeMillis();
        ResponseEntity<String> response = forward(up, path, mapper.writeValueAsString(json));
        JsonNode inputs = json.get(inputsField);
        int count = inputs == null || inputs.isNull() ? 0 : inputs.isArray() ? inputs.size() : 1;
        requestLog.embedSummary(kind, json, response.getStatusCode().value(), System.currentTimeMillis() - startedAt, count);
        return response;
    }

    /** Client request body must be a JSON object; anything else is the caller's error (400), not ours (500). */
    private ObjectNode parseRequestObject(String body) {
        JsonNode node;
        try {
            node = mapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new InvalidRequestException("request body is not valid JSON: " + e.getOriginalMessage());
        }
        if (!(node instanceof ObjectNode json)) {
            throw new InvalidRequestException("request body must be a JSON object");
        }
        return json;
    }
    /**
     * Loads the guardrail text, dropping a leading HTML comment block. That block holds the
     * maintenance note for whoever edits the file (see guardrails.md) — it is for humans, and
     * would otherwise be prepended to every single request as dead tokens.
     */
    private static String loadGuardrailPrompt() {
        try {
            String raw = new String(new ClassPathResource("prompts/guardrails.md").getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            return raw.replaceFirst("(?s)^\\s*<!--.*?-->", "").strip();
        } catch (IOException e) {
            throw new IllegalStateException("prompts/guardrails.md ausente do classpath", e);
        }
    }

    /**
     * Applies the centralized security guardrail to chat completions. Prepended to the caller's
     * own system message (if any) rather than replacing it — app-specific system prompts (scope,
     * tool-call rules, output format) keep working on top. Prepending also keeps the guardrail a
     * shared prefix across callers, which is what llama.cpp's prompt cache can actually reuse.
     *
     * <p>Skipped for schema-constrained requests ({@code response_format}): those are data
     * extraction jobs, not conversations. The grammar makes a prose refusal impossible anyway,
     * so the guardrail would only burn tokens on every document of a bulk pipeline while telling
     * the model to "refuse off-topic questions" about a task that asks no question.
     */
    private void mergeGuardrailSystemPrompt(ObjectNode json) {
        JsonNode messagesNode = json.get("messages");
        if (!(messagesNode instanceof ArrayNode messages) || json.hasNonNull("response_format")) {
            return;
        }
        JsonNode first = messages.isEmpty() ? null : messages.get(0);
        // Only merge into a plain-text system message. A non-textual content (multimodal content
        // blocks) would read back as "" and silently wipe the caller's prompt, so prepend a
        // separate message instead.
        if (first instanceof ObjectNode firstObj
                && "system".equals(firstObj.path("role").asText())
                && firstObj.path("content").isTextual()) {
            firstObj.put("content", guardrailPrompt + "\n\n---\n\n" + firstObj.path("content").asText());
        } else {
            ObjectNode systemMessage = mapper.createObjectNode();
            systemMessage.put("role", "system");
            systemMessage.put("content", guardrailPrompt);
            messages.insert(0, systemMessage);
        }
    }

    /** Best-effort: never lets a parse failure surface, response body already parsed once. */
    private JsonNode parseResponseBody(ResponseEntity<String> response) {
        if (response.getBody() == null) {
            return MissingNode.getInstance();
        }
        try {
            return mapper.readTree(response.getBody());
        } catch (IOException e) {
            return MissingNode.getInstance();
        }
    }

    private static void fillDefaultModel(ObjectNode json, ProxyProperties.Upstream up) {
        if (!json.hasNonNull("model") && up.defaultModel() != null && !up.defaultModel().isBlank()) {
            json.put("model", up.defaultModel());
        }
    }

    private ResponseEntity<String> forward(ProxyProperties.Upstream up, String path, String payload) {
        var spec = http.post(up, path);
        try {
            return UpstreamHttp.stripHopByHopHeaders(spec.body(payload)
                    .retrieve()
                    // s -> true: every status (including 4xx/5xx) is "handled" by this no-op, so
                    // RestClient's own exception-throwing default never runs and the caller gets the
                    // upstream's real status/body — e.g. a 429 rate-limit reaches LazyInvest as 429,
                    // not as an opaque Spring 500.
                    .onStatus(s -> true, (req, res) -> { })
                    .toEntity(String.class));
        } catch (RestClientException e) {
            if (UpstreamFailure.classify(e) != null) {
                requestLog.backendDownThrottled(up.baseUrl(), path, e);
            }
            throw e;
        }
    }

    /** Rough token estimate (~4 chars/token) over message contents and tool specs — enough to pick a route.
     *  SPEC-chat-backlog C.I4: o chat manda boa parte dos ~21k tokens em {@code tools} e conteúdo em partes
     *  ({@code [{type:text,text:…}]}); sem eles o limiar de speed decidia sobre uma fração do tamanho real. */
    static int estimateTokens(JsonNode json) {
        int chars = 0;
        for (JsonNode m : json.path("messages")) {
            JsonNode content = m.path("content");
            if (content.isTextual()) {
                chars += content.asText().length();
            } else if (content.isArray()) {
                for (JsonNode part : content) chars += part.path("text").asText("").length();
            }
        }
        JsonNode tools = json.path("tools");
        if (tools.isArray()) chars += tools.toString().length();
        return chars / 4;
    }
}
