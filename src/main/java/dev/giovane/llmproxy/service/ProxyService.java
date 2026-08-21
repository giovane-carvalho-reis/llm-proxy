package dev.giovane.llmproxy.service;

import dev.giovane.llmproxy.config.ProxyProperties;
import dev.giovane.llmproxy.router.Router;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * OpenAI-compatible reverse proxy logic. Forwards the request body verbatim (Jackson
 * round-trip only, so llama.cpp-only fields like response_format/cache_prompt/
 * enable_thinking survive) to whichever upstream {@link Router} picks.
 */
@Service
public class ProxyService {

    private static final Logger log = LoggerFactory.getLogger(ProxyService.class);

    // RFC 7230 §6.1 hop-by-hop headers. forward() buffers the response body into a String
    // (toEntity(String.class)) before Tomcat writes it, so Tomcat computes its own
    // Transfer-Encoding/Content-Length — forwarding the upstream's Transfer-Encoding verbatim
    // makes the servlet response carry two, which downstream HTTP clients (e.g. the BFF's
    // RestClient) reject with "multiple Transfer-Encoding headers". forwardStreaming() strips the
    // same set for the same reason, even though it isn't buffering — Tomcat still computes its
    // own framing for the async response.
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade");

    private final ProxyProperties props;
    private final dev.giovane.llmproxy.config.LlmConfigState configState;
    private final ObjectMapper mapper;
    private final RestClient http;
    private final String guardrailPrompt;

    public ProxyService(ProxyProperties props, dev.giovane.llmproxy.config.LlmConfigState configState,
            ObjectMapper mapper) {
        this.props = props;
        this.configState = configState;
        this.mapper = mapper;
        this.guardrailPrompt = loadGuardrailPrompt();
        // llama.cpp prefill on big contexts is slow; give the upstream a long read timeout
        // (the controller thread blocks, matching the client's own long timeout).
        var settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withReadTimeout(props.routing().readTimeout());
        // StringHttpMessageConverter defaults to ISO-8859-1 when the upstream's
        // Content-Type has no charset param (llama.cpp/llama-swap's "application/json"
        // doesn't set one) — that silently mojibakes every non-ASCII char (UTF-8 bytes
        // read as Latin-1) before it even reaches downstream consumers like the BFF.
        var utf8StringConverter = new StringHttpMessageConverter(StandardCharsets.UTF_8);
        this.http = RestClient.builder()
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .messageConverters(converters -> {
                    converters.removeIf(c -> c instanceof StringHttpMessageConverter);
                    converters.add(0, utf8StringConverter);
                })
                .build();
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
        ObjectNode json = (ObjectNode) mapper.readTree(body);

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
        if (up.temperature() != null) {
            json.put("temperature", up.temperature());
        }
        if (Router.OPENROUTER.equals(target)) {
            pinOpenRouterProvider(json);
        }
        String payload = mapper.writeValueAsString(json);

        // stream:true bypasses forward() entirely — that method fully buffers the upstream body
        // into a String before returning, which for a streaming caller (LazyInvest's SSE chat)
        // means zero bytes reach the client until the ENTIRE generation finishes. That silently
        // turned every "final answer" stream into a single blocking wait as long as the model's
        // full generation time, long enough to trip idle-timeout watchdogs downstream that assume
        // tokens arrive incrementally. See forwardStreaming() for the real passthrough.
        if (json.path("stream").asBoolean(false)) {
            return forwardStreaming(up, "/v1/chat/completions", payload, target, json);
        }

        long startedAt = System.currentTimeMillis();
        ResponseEntity<String> response = forward(up, "/v1/chat/completions", payload);
        long latencyMs = System.currentTimeMillis() - startedAt;

        // Single parse of the response body, shared by every log line below — logOpenRouterProvider/
        // logDraftAcceptance/logRequestSummary each used to re-parse it independently.
        JsonNode resp = parseResponseBody(response);
        if (Router.OPENROUTER.equals(target)) {
            logOpenRouterProvider(json, resp);
        } else {
            logDraftAcceptance(json, resp);
        }
        logRequestSummary(target, json, resp, response.getStatusCode().value(), latencyMs);

        StreamingResponseBody wrapped = out -> {
            if (response.getBody() != null) {
                out.write(response.getBody().getBytes(StandardCharsets.UTF_8));
            }
        };
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).body(wrapped);
    }

    /** Embeddings passthrough — always the local bge-m3 llama-server; no routing to decide. */
    public ResponseEntity<String> embeddings(String body) throws Exception {
        ObjectNode json = (ObjectNode) mapper.readTree(body);
        ProxyProperties.Upstream up = props.embeddings();
        fillDefaultModel(json, up);
        return forward(up, "/v1/embeddings", mapper.writeValueAsString(json));
    }

    /** Rerank passthrough — always the local bge-reranker-v2-m3 llama-server; no routing to decide. */
    public ResponseEntity<String> rerank(String body) throws Exception {
        ObjectNode json = (ObjectNode) mapper.readTree(body);
        ProxyProperties.Upstream up = props.rerank();
        fillDefaultModel(json, up);
        return forward(up, "/v1/rerank", mapper.writeValueAsString(json));
    }

    /** EmbeddingClient.is_available() — relayed to llama-swap's /health. */
    public ResponseEntity<String> health() {
        return stripHopByHopHeaders(http.get()
                .uri(props.embeddings().baseUrl() + "/health")
                .retrieve()
                .onStatus(s -> false, (req, res) -> { })
                .toEntity(String.class));
    }

    /** is_available() health check — proxied to llama.cpp (the default/local backend). */
    public ResponseEntity<String> models() {
        return stripHopByHopHeaders(http.get()
                .uri(props.llamaCpp().baseUrl() + "/v1/models")
                .retrieve()
                .onStatus(s -> false, (req, res) -> { })
                .toEntity(String.class));
    }

    /**
     * Same upstream as {@link #models()}, filtered to {@code llm-proxy.local-chat-models} —
     * llama-swap's /v1/models lists embeddings/rerank models too and carries no group field
     * to tell them apart, so the chat/embeddings split can only come from our own config.
     */
    public ResponseEntity<String> chatModels() {
        ResponseEntity<String> upstream = models();
        if (!upstream.getStatusCode().is2xxSuccessful() || upstream.getBody() == null) {
            return upstream;
        }
        try {
            ObjectNode json = (ObjectNode) mapper.readTree(upstream.getBody());
            JsonNode data = json.get("data");
            if (data instanceof ArrayNode array) {
                Set<String> allowed = Set.copyOf(props.localChatModels());
                ArrayNode filtered = mapper.createArrayNode();
                array.forEach(model -> {
                    if (allowed.contains(model.path("id").asText())) {
                        filtered.add(model);
                    }
                });
                json.set("data", filtered);
            }
            // Content-Length in upstream.getHeaders() was computed for the unfiltered body —
            // reusing it here (now shorter) would make the client wait for bytes that never
            // arrive, hanging until its read timeout. Only Content-Type carries over; Spring
            // MVC computes the correct Content-Length itself from the actual response body.
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(upstream.getHeaders().getContentType());
            return ResponseEntity.status(upstream.getStatusCode()).headers(headers)
                    .body(mapper.writeValueAsString(json));
        } catch (IOException e) {
            return upstream;
        }
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

    /**
     * Sem isto o OpenRouter roteia livremente o mesmo model id entre backends de
     * inferência diferentes por chamada — fidelidade de tool-calling varia por backend
     * mesmo com temperature=0.0 (DEBT-TOOLCALL-001, project-specs/lazyinvest/decisions.md).
     * require_parameters filtra candidatos que não suportam os parâmetros pedidos (ex.:
     * tools); allow_fallbacks evita cair silenciosamente num backend alternativo em vez de
     * errar.
     */
    private void pinOpenRouterProvider(ObjectNode json) {
        if (!json.has("provider")) {
            ObjectNode provider = mapper.createObjectNode();
            provider.put("allow_fallbacks", false);
            provider.put("require_parameters", true);
            json.set("provider", provider);
        }
    }

    /** Best-effort: never lets a parse failure surface, response body already parsed once. */
    private JsonNode parseResponseBody(ResponseEntity<String> response) {
        if (response.getBody() == null) {
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }
        try {
            return mapper.readTree(response.getBody());
        } catch (IOException e) {
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }
    }

    /** Best-effort: nunca falha a resposta ao cliente por causa de um log. */
    private void logOpenRouterProvider(ObjectNode request, JsonNode resp) {
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
     * read it here, before it's discarded. Best-effort: never fails the response over a log.
     */
    private void logDraftAcceptance(ObjectNode request, JsonNode resp) {
        JsonNode timings = resp.path("timings");
        int draftN = timings.path("draft_n").asInt(0);
        if (draftN <= 0) {
            return;
        }
        int accepted = timings.path("draft_n_accepted").asInt(0);
        log.info("mtp model={} draft={}/{} accepted={}%",
                request.path("model").asText("?"), accepted, draftN, Math.round(100.0 * accepted / draftN));
    }

    /**
     * One line per chat request — route/model/status/latency/tokens. Before this, the proxy's
     * only chat-path logs were openrouter-provider and MTP-acceptance (both conditional, both
     * silent on the local/no-MTP path), so a request with no OpenRouter fallback and no
     * speculative decoding left zero trace of route chosen, latency, or token counts — nothing
     * to measure the temperature/guardrail changes in this round against. Usage fields come
     * from the upstream's own OpenAI-shaped "usage" object; missing on some llama.cpp builds,
     * so 0 there just means "not reported", not "zero tokens".
     */
    private void logRequestSummary(String target, ObjectNode request, JsonNode resp, int status, long latencyMs) {
        JsonNode usage = resp.path("usage");
        log.info("chat route={} model={} status={} latencyMs={} promptTokens={} completionTokens={}",
                target, request.path("model").asText("?"), status, latencyMs,
                usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0));
    }

    private static void fillDefaultModel(ObjectNode json, ProxyProperties.Upstream up) {
        if (!json.hasNonNull("model") && up.defaultModel() != null && !up.defaultModel().isBlank()) {
            json.put("model", up.defaultModel());
        }
    }

    private ResponseEntity<String> forward(ProxyProperties.Upstream up, String path, String payload) {
        var spec = http.post()
                .uri(up.baseUrl() + path)
                .contentType(MediaType.APPLICATION_JSON);
        if (up.apiKey() != null && !up.apiKey().isBlank()) {
            spec = spec.header("Authorization", "Bearer " + up.apiKey());
        }
        return stripHopByHopHeaders(spec.body(payload)
                .retrieve()
                // s -> true: every status (including 4xx/5xx) is "handled" by this no-op, so
                // RestClient's own exception-throwing default never runs and the caller gets the
                // upstream's real status/body — e.g. a 429 rate-limit reaches LazyInvest as 429,
                // not as an opaque Spring 500 (the predicate here used to be `s -> false`, which
                // never matches, so the default handler ran anyway and threw on every non-2xx).
                .onStatus(s -> true, (req, res) -> { })
                .toEntity(String.class));
    }

    /**
     * Real SSE passthrough for {@code stream: true} chat completions — same shape as
     * lazy-invest-bff's {@code ProxyController.proxyChatStream}. {@code exchange(fn, false)}
     * keeps the upstream connection open past the callback (the {@code false} disables
     * RestClient's default auto-close), so the {@link StreamingResponseBody} below can read and
     * forward the body lazily, on Spring MVC's async dispatch thread, instead of everything
     * being read eagerly inside this method like {@link #forward} does.
     */
    private ResponseEntity<StreamingResponseBody> forwardStreaming(ProxyProperties.Upstream up, String path,
            String payload, String target, ObjectNode request) {
        var spec = http.post()
                .uri(up.baseUrl() + path)
                .contentType(MediaType.APPLICATION_JSON);
        if (up.apiKey() != null && !up.apiKey().isBlank()) {
            spec = spec.header("Authorization", "Bearer " + up.apiKey());
        }
        long startedAt = System.currentTimeMillis();
        return spec.body(payload).exchange((req, res) -> {
            HttpHeaders headers = new HttpHeaders();
            res.getHeaders().forEach((name, values) -> {
                // Content-Length also excluded here (unlike forward()'s stripHopByHopHeaders,
                // which keeps it because the buffered body there has that exact byte length): a
                // compliant SSE response never declares one upfront, so forwarding a stale value
                // — from a misbehaving upstream or an intermediate proxy — would make Tomcat
                // frame the chunked body wrong instead of computing its own framing.
                if (!HOP_BY_HOP_HEADERS.contains(name.toLowerCase()) && !"content-length".equalsIgnoreCase(name)) {
                    headers.put(name, values);
                }
            });
            StreamingResponseBody streamBody = out -> {
                // Can't parse token/usage counts out of an SSE body (see completions()'s
                // non-streaming branch for that) — log what's available without pretending to
                // have counts this path structurally can't produce.
                try {
                    try (InputStream in = res.getBody()) {
                        byte[] buf = new byte[512];
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            out.write(buf, 0, n);
                            out.flush(); // each SSE event must reach the client as it arrives, not batched
                        }
                    }
                } catch (IOException clientGone) {
                    // The write side (out) failing mid-stream is the client disconnecting —
                    // same "expected, not a bug" case the BFF's proxyChatStream and LazyInvest's
                    // SSE heartbeat both already treat as routine, not an error. Left uncaught,
                    // this surfaced as a full ERROR stack trace ("Broken pipe") from Tomcat for
                    // the ordinary case of a browser tab closing mid-response.
                    log.debug("[chat-stream] client disconnected mid-stream | route={} model={}",
                            target, request.path("model").asText("?"), clientGone);
                } finally {
                    log.info("chat route={} model={} status={} latencyMs={} (stream)",
                            target, request.path("model").asText("?"), res.getStatusCode().value(),
                            System.currentTimeMillis() - startedAt);
                }
            };
            return ResponseEntity.status(res.getStatusCode()).headers(headers).body(streamBody);
        }, false);
    }

    private static ResponseEntity<String> stripHopByHopHeaders(ResponseEntity<String> upstream) {
        HttpHeaders headers = new HttpHeaders();
        upstream.getHeaders().forEach((name, values) -> {
            if (!HOP_BY_HOP_HEADERS.contains(name.toLowerCase())) {
                headers.put(name, values);
            }
        });
        // The body was already decoded as UTF-8 (see the RestClient's StringHttpMessageConverter
        // above). But llama.cpp/llama-swap's Content-Type ("application/json") carries no charset
        // param, and Spring MVC's own outbound StringHttpMessageConverter defaults to ISO-8859-1
        // when none is declared — it would re-encode this same string wrong on the way out unless
        // we pin the charset explicitly here.
        MediaType contentType = headers.getContentType();
        if (contentType != null && contentType.getCharset() == null) {
            headers.setContentType(new MediaType(contentType, StandardCharsets.UTF_8));
        }
        return new ResponseEntity<>(upstream.getBody(), headers, upstream.getStatusCode());
    }

    /** Rough token estimate (~4 chars/token) over message contents — enough to pick a route. */
    private static int estimateTokens(JsonNode json) {
        JsonNode messages = json.get("messages");
        if (messages == null || !messages.isArray()) {
            return 0;
        }
        int chars = 0;
        for (JsonNode m : messages) {
            JsonNode content = m.get("content");
            if (content != null && content.isTextual()) {
                chars += content.asText().length();
            }
        }
        return chars / 4;
    }
}
