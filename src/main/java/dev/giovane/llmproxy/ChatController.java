package dev.giovane.llmproxy;

import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * OpenAI-compatible reverse proxy. It forwards the request body verbatim (Jackson
 * round-trip only, so llama.cpp-only fields like response_format/cache_prompt/
 * enable_thinking survive) to whichever upstream {@link Router} picks. Callers never
 * learn which backend ran — they just send a model name and, optionally, routing hints:
 *
 *   X-Llm-Route:    llama-cpp | openrouter | auto   (default auto)
 *   X-Llm-Priority: speed                            (only meaningful in auto)
 */
@RestController
class ChatController {

    private final ProxyProperties props;
    private final ObjectMapper mapper;
    private final RestClient http;

    ChatController(ProxyProperties props, ObjectMapper mapper) {
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

    @PostMapping("/v1/chat/completions")
    ResponseEntity<String> completions(
            @RequestBody String body,
            @RequestHeader(value = "X-Llm-Route", defaultValue = "auto") String route,
            @RequestHeader(value = "X-Llm-Priority", required = false) String priority) throws Exception {

        ObjectNode json = (ObjectNode) mapper.readTree(body);
        boolean speed = "speed".equalsIgnoreCase(priority);
        String target = Router.resolve(route, speed, estimateTokens(json), props.routing().speedTokenThreshold());

        ProxyProperties.Upstream up = Router.OPENROUTER.equals(target) ? props.openrouter() : props.llamaCpp();

        // Fill in the backend's default model when the caller didn't pin one — the whole point
        // of "auto" is that the caller doesn't know which backend (and thus which model id) runs.
        if (!json.hasNonNull("model") && up.defaultModel() != null && !up.defaultModel().isBlank()) {
            json.put("model", up.defaultModel());
        }

        return forward(up, "/v1/chat/completions", mapper.writeValueAsString(json));
    }

    /** Embeddings passthrough — always the local bge-m3 llama-server; no routing to decide. */
    @PostMapping("/v1/embeddings")
    ResponseEntity<String> embeddings(@RequestBody String body) throws Exception {
        ObjectNode json = (ObjectNode) mapper.readTree(body);
        ProxyProperties.Upstream up = props.embeddings();
        if (!json.hasNonNull("model") && up.defaultModel() != null && !up.defaultModel().isBlank()) {
            json.put("model", up.defaultModel());
        }
        return forward(up, "/v1/embeddings", mapper.writeValueAsString(json));
    }

    /** EmbeddingClient.is_available() — relayed to llama-swap's /health. */
    @GetMapping("/health")
    ResponseEntity<String> health() {
        return http.get()
                .uri(props.embeddings().baseUrl() + "/health")
                .retrieve()
                .onStatus(s -> false, (req, res) -> { })
                .toEntity(String.class);
    }

    /** is_available() health check — proxied to llama.cpp (the default/local backend). */
    @GetMapping("/v1/models")
    ResponseEntity<String> models() {
        return http.get()
                .uri(props.llamaCpp().baseUrl() + "/v1/models")
                .retrieve()
                .onStatus(s -> false, (req, res) -> { })
                .toEntity(String.class);
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
