package dev.giovane.llmproxy.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Upstream backends and routing knobs. Both backends speak the OpenAI dialect,
 * so an Upstream is just a base-url (+ optional key + default model) that the
 * proxy appends "/v1/chat/completions" and "/v1/models" to.
 *
 * base-url must include everything before "/v1": llama.cpp is "http://host:8080",
 * OpenRouter is "https://openrouter.ai/api".
 */
@ConfigurationProperties("llm-proxy")
public record ProxyProperties(Upstream llamaCpp, Upstream openrouter, Upstream embeddings, Routing routing) {

    public record Upstream(String baseUrl, String apiKey, String defaultModel) {
    }

    /** speedTokenThreshold: in "auto", a speed-hinted request bigger than this stays local. */
    public record Routing(int speedTokenThreshold, Duration readTimeout) {
    }
}
