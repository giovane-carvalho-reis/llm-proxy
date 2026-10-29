package dev.giovane.llmproxy.config;

import dev.giovane.llmproxy.router.Router;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

/**
 * In-memory-only runtime overrides for a subset of {@link ProxyProperties}. Nothing here is
 * persisted: a process restart drops every override and falls back to the env-var-backed
 * {@link ProxyProperties} again — by design, so no separate token store exists.
 *
 * <p>{@code props.embeddings()} deliberately has no override slot: the embedding model is
 * coupled to the already-generated vector index, and swapping it at runtime would corrupt
 * existing searches.
 */
@Component
public class LlmConfigState {

    /** Overridable fields; {@code null} means "no override, use the env-backed value". */
    private record Overrides(String openrouterApiKey, String openrouterDefaultModel, String llamaCppDefaultModel) {
        static final Overrides EMPTY = new Overrides(null, null, null);
    }

    private final AtomicReference<Overrides> overrides = new AtomicReference<>(Overrides.EMPTY);

    public String openrouterApiKeyOverride() {
        return overrides.get().openrouterApiKey();
    }

    public String openrouterDefaultModelOverride() {
        return overrides.get().openrouterDefaultModel();
    }

    public String llamaCppDefaultModelOverride() {
        return overrides.get().llamaCppDefaultModel();
    }

    /**
     * Sets the OpenRouter API key override. {@code null} leaves the current override untouched
     * (used by the PUT endpoint to mean "field absent"); an empty string clears the override.
     */
    public void setOpenrouterApiKey(String value) {
        update(value, (o, v) -> new Overrides(v, o.openrouterDefaultModel(), o.llamaCppDefaultModel()));
    }

    /** Same "absent vs. empty vs. value" semantics as {@link #setOpenrouterApiKey(String)}. */
    public void setOpenrouterDefaultModel(String value) {
        update(value, (o, v) -> new Overrides(o.openrouterApiKey(), v, o.llamaCppDefaultModel()));
    }

    /** Same "absent vs. empty vs. value" semantics as {@link #setOpenrouterApiKey(String)}. */
    public void setLlamaCppDefaultModel(String value) {
        update(value, (o, v) -> new Overrides(o.openrouterApiKey(), o.openrouterDefaultModel(), v));
    }

    private void update(String value, BiFunction<Overrides, String, Overrides> change) {
        if (value == null) {
            return;
        }
        String next = normalize(value);
        overrides.updateAndGet(o -> change.apply(o, next));
    }

    private static String normalize(String value) {
        return value.isEmpty() ? null : value;
    }

    /**
     * Resolves the effective {@link ProxyProperties.Upstream} for {@code target} ("llama-cpp" or
     * "openrouter"), applying whatever override is currently set on top of {@code base}. Returns
     * {@code base} unchanged when nothing is overridden for that target.
     */
    public ProxyProperties.Upstream effective(String target, ProxyProperties.Upstream base) {
        Overrides current = overrides.get();
        boolean openrouter = Router.OPENROUTER.equals(target);
        String apiKey = openrouter && current.openrouterApiKey() != null ? current.openrouterApiKey() : base.apiKey();
        String defaultModel = base.defaultModel();
        if (openrouter && current.openrouterDefaultModel() != null) {
            defaultModel = current.openrouterDefaultModel();
        } else if (!openrouter && current.llamaCppDefaultModel() != null) {
            defaultModel = current.llamaCppDefaultModel();
        }
        if (Objects.equals(apiKey, base.apiKey()) && Objects.equals(defaultModel, base.defaultModel())) {
            return base;
        }
        return new ProxyProperties.Upstream(base.baseUrl(), apiKey, defaultModel, base.temperature());
    }
}
