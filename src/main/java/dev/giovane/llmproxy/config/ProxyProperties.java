package dev.giovane.llmproxy.config;

import java.time.Duration;
import java.util.List;

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
public record ProxyProperties(Upstream llamaCpp, Upstream openrouter, Upstream embeddings, Upstream rerank,
        Routing routing, String adminToken, List<String> localChatModels,
        List<String> openrouterModelsWithoutTemperature) {

    /** Prefixos de modelo do OpenRouter que recusam {@code temperature} (modelos de raciocínio da OpenAI).
     *  Para eles a temperatura forçada não é injetada: com tools o proxy exige {@code require_parameters}, e
     *  um parâmetro não suportado zera os endpoints (404 "No endpoints found…"). */
    public boolean rejectsTemperature(String model) {
        return model != null && openrouterModelsWithoutTemperature != null
                && openrouterModelsWithoutTemperature.stream().anyMatch(p -> !p.isBlank() && model.startsWith(p.trim()));
    }

    public record Upstream(String baseUrl, String apiKey, String defaultModel, Double temperature) {
    }

    /** speedTokenThreshold: in "auto", a speed-hinted request bigger than this stays local. */
    public record Routing(int speedTokenThreshold, Duration readTimeout) {
    }
}
