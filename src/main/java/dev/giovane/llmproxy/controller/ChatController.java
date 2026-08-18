package dev.giovane.llmproxy.controller;

import dev.giovane.llmproxy.service.ProxyService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * OpenAI-compatible reverse proxy endpoints. Callers never learn which backend ran —
 * they just send a model name and, optionally, routing hints:
 *
 *   X-Llm-Route:    llama-cpp | openrouter | auto   (default auto)
 *   X-Llm-Priority: speed                            (only meaningful in auto)
 */
@RestController
public class ChatController {

    private final ProxyService service;

    ChatController(ProxyService service) {
        this.service = service;
    }

    @PostMapping("/v1/chat/completions")
    ResponseEntity<String> completions(
            @RequestBody String body,
            @RequestHeader(value = "X-Llm-Route", defaultValue = "auto") String route,
            @RequestHeader(value = "X-Llm-Priority", required = false) String priority) throws Exception {
        return service.completions(body, route, priority);
    }

    @PostMapping("/v1/embeddings")
    ResponseEntity<String> embeddings(@RequestBody String body) throws Exception {
        return service.embeddings(body);
    }

    @PostMapping("/v1/rerank")
    ResponseEntity<String> rerank(@RequestBody String body) throws Exception {
        return service.rerank(body);
    }

    @GetMapping("/health")
    ResponseEntity<String> health() {
        return service.health();
    }

    @GetMapping("/v1/models")
    ResponseEntity<String> models() {
        return service.models();
    }

    /** Same as /v1/models, filtered to chat-capable local models (excludes embeddings/rerank). */
    @GetMapping("/v1/models/chat")
    ResponseEntity<String> chatModels() {
        return service.chatModels();
    }
}
