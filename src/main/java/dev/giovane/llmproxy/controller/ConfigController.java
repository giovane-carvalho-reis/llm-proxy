package dev.giovane.llmproxy.controller;

import dev.giovane.llmproxy.config.LlmConfigState;
import dev.giovane.llmproxy.config.ProxyProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Read/write endpoint for the in-memory runtime config overrides ({@link LlmConfigState}).
 * Protected by {@code AdminTokenInterceptor} — never reachable
 * without a valid {@code X-Admin-Token}.
 */
@RestController
public class ConfigController {

    private static final Logger log = LoggerFactory.getLogger(ConfigController.class);

    private final ProxyProperties props;
    private final LlmConfigState configState;

    public ConfigController(ProxyProperties props, LlmConfigState configState) {
        this.props = props;
        this.configState = configState;
    }

    @GetMapping("/v1/config")
    ConfigView get() {
        String apiKey = configState.openrouterApiKeyOverride() != null
                ? configState.openrouterApiKeyOverride()
                : props.openrouter().apiKey();
        boolean apiKeySet = apiKey != null && !apiKey.isBlank();

        String openrouterModel = configState.openrouterDefaultModelOverride();
        String llamaCppModel = configState.llamaCppDefaultModelOverride();

        return new ConfigView(
                new ModelValue(
                        openrouterModel != null ? openrouterModel : props.openrouter().defaultModel(),
                        openrouterModel != null ? "override" : "env"),
                new ModelValue(
                        llamaCppModel != null ? llamaCppModel : props.llamaCpp().defaultModel(),
                        llamaCppModel != null ? "override" : "env"),
                apiKeySet,
                apiKeySet ? maskApiKey(apiKey) : null);
    }

    /**
     * Partial update semantics: a field absent from the JSON body (or explicitly {@code null})
     * leaves the corresponding override untouched; an empty string clears the override (falling
     * back to the env var); any other string sets the override.
     */
    @PutMapping("/v1/config")
    ConfigView put(@RequestBody UpdateRequest request) {
        List<String> fields = new ArrayList<>();
        if (request.openrouterApiKey() != null) {
            fields.add("openrouterApiKey");
        }
        if (request.openrouterDefaultModel() != null) {
            fields.add("openrouterDefaultModel");
        }
        if (request.llamaCppDefaultModel() != null) {
            fields.add("llamaCppDefaultModel");
        }
        log.info("config alterada campos={}", fields);
        configState.setOpenrouterApiKey(request.openrouterApiKey());
        configState.setOpenrouterDefaultModel(request.openrouterDefaultModel());
        configState.setLlamaCppDefaultModel(request.llamaCppDefaultModel());
        return get();
    }

    private static String maskApiKey(String apiKey) {
        if (apiKey.length() < 8) {
            return "***";
        }
        return "…" + apiKey.substring(apiKey.length() - 4);
    }

    public record UpdateRequest(String openrouterApiKey, String openrouterDefaultModel, String llamaCppDefaultModel) {
    }

    public record ModelValue(String value, String source) {
    }

    public record ConfigView(ModelValue openrouterDefaultModel, ModelValue llamaCppDefaultModel,
            boolean openrouterApiKeySet, String openrouterApiKeyMasked) {
    }
}
