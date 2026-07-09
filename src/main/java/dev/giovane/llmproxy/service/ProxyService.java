package dev.giovane.llmproxy.service;

import dev.giovane.llmproxy.config.ProxyProperties;
import dev.giovane.llmproxy.router.Router;

import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * OpenAI-compatible reverse proxy logic. Forwards the request body verbatim (Jackson
 * round-trip only, so llama.cpp-only fields like response_format/cache_prompt/
 * enable_thinking survive) to whichever upstream {@link Router} picks.
 */
@Service
public class ProxyService {

    private final ProxyProperties props;
    private final ObjectMapper mapper;
    private final RestClient http;

    public ProxyService(ProxyProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        // llama.cpp prefill on big contexts is slow; give the upstream a long read timeout
        // (the controller thread blocks, matching the client's own long timeout).
        var settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withReadTimeout(props.routing().readTimeout());
        this.http = RestClient.builder()
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }

    public ResponseEntity<String> completions(String body, String route, String priority) throws Exception {
        ObjectNode json = (ObjectNode) mapper.readTree(body);
        boolean speed = "speed".equalsIgnoreCase(priority);
        String target = Router.resolve(route, speed, estimateTokens(json), props.routing().speedTokenThreshold());

        ProxyProperties.Upstream up = Router.OPENROUTER.equals(target) ? props.openrouter() : props.llamaCpp();

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
        return http.get()
                .uri(props.embeddings().baseUrl() + "/health")
                .retrieve()
                .onStatus(s -> false, (req, res) -> { })
                .toEntity(String.class);
    }

    /** is_available() health check — proxied to llama.cpp (the default/local backend). */
    public ResponseEntity<String> models() {
        return http.get()
                .uri(props.llamaCpp().baseUrl() + "/v1/models")
                .retrieve()
                .onStatus(s -> false, (req, res) -> { })
                .toEntity(String.class);
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
        return spec.body(payload)
                .retrieve()
                .onStatus(s -> false, (req, res) -> { })   // relay upstream status/body instead of throwing
                .toEntity(String.class);
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
