package dev.giovane.llmproxy.service;

import dev.giovane.llmproxy.config.ProxyProperties;
import dev.giovane.llmproxy.router.Router;

import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
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

    // RFC 7230 §6.1 hop-by-hop headers. The response body is fully buffered into a String
    // (toEntity(String.class)) before Tomcat writes it, so Tomcat computes its own
    // Transfer-Encoding/Content-Length — forwarding the upstream's Transfer-Encoding verbatim
    // makes the servlet response carry two, which downstream HTTP clients (e.g. the BFF's
    // RestClient) reject with "multiple Transfer-Encoding headers".
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

    public ResponseEntity<String> completions(String body, String route, String priority) throws Exception {
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
        return forward(up, "/v1/chat/completions", mapper.writeValueAsString(json));
    }

    /** Embeddings passthrough — always the local bge-m3 llama-server; no routing to decide. */
    public ResponseEntity<String> embeddings(String body) throws Exception {
        ObjectNode json = (ObjectNode) mapper.readTree(body);
        ProxyProperties.Upstream up = props.embeddings();
        fillDefaultModel(json, up);
        return forward(up, "/v1/embeddings", mapper.writeValueAsString(json));
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
                .onStatus(s -> false, (req, res) -> { })   // relay upstream status/body instead of throwing
                .toEntity(String.class));
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
