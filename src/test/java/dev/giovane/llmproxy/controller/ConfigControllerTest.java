package dev.giovane.llmproxy.controller;

import dev.giovane.llmproxy.config.LlmConfigState;
import dev.giovane.llmproxy.config.ProxyProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ConfigController} against a real {@link LlmConfigState} (no mocking of
 * the collaborator we are actually asserting behaviour on) — verifies partial-update semantics
 * and that the plaintext API key never leaks through GET.
 */
class ConfigControllerTest {

    private static final ProxyProperties.Upstream OPENROUTER =
            new ProxyProperties.Upstream("https://openrouter.ai/api", "env-secret-key", "env-or-model");
    private static final ProxyProperties.Upstream LLAMA_CPP =
            new ProxyProperties.Upstream("http://localhost:8080", null, "env-llama-model");
    private static final ProxyProperties.Upstream EMBEDDINGS =
            new ProxyProperties.Upstream("http://localhost:8081", null, "env-embed-model");

    private ProxyProperties props;
    private LlmConfigState configState;
    private ConfigController controller;

    @BeforeEach
    void setUp() {
        props = new ProxyProperties(LLAMA_CPP, OPENROUTER, EMBEDDINGS, EMBEDDINGS,
                new ProxyProperties.Routing(8000, Duration.ofMinutes(1)), "admin-token", java.util.List.of());
        configState = new LlmConfigState();
        controller = new ConfigController(props, configState);
    }

    @Test
    void getNeverExposesApiKeyInClearEvenWhenSet() {
        ConfigController.ConfigView view = controller.get();

        assertThat(view.openrouterApiKeySet()).isTrue();
        assertThat(view.openrouterApiKeyMasked()).isNotNull();
        assertThat(view.openrouterApiKeyMasked()).doesNotContain(OPENROUTER.apiKey());
        assertThat(view.openrouterApiKeyMasked()).endsWith(OPENROUTER.apiKey().substring(OPENROUTER.apiKey().length() - 4));
    }

    @Test
    void getReportsApiKeyUnsetWhenNoEnvKeyAndNoOverride() {
        ProxyProperties propsNoKey = new ProxyProperties(LLAMA_CPP,
                new ProxyProperties.Upstream(OPENROUTER.baseUrl(), null, OPENROUTER.defaultModel()),
                EMBEDDINGS, EMBEDDINGS, new ProxyProperties.Routing(8000, Duration.ofMinutes(1)), "admin-token",
                java.util.List.of());
        ConfigController noKeyController = new ConfigController(propsNoKey, configState);

        ConfigController.ConfigView view = noKeyController.get();

        assertThat(view.openrouterApiKeySet()).isFalse();
        assertThat(view.openrouterApiKeyMasked()).isNull();
    }

    @Test
    void putWithOnlyOneFieldLeavesOtherOverridesUntouched() {
        controller.put(new ConfigController.UpdateRequest("override-key", "override-or-model", "override-llama-model"));

        controller.put(new ConfigController.UpdateRequest(null, "second-or-model", null));

        ConfigController.ConfigView view = controller.get();
        assertThat(view.openrouterDefaultModel().value()).isEqualTo("second-or-model");
        assertThat(view.openrouterDefaultModel().source()).isEqualTo("override");
        // untouched fields keep their previously-set override values
        assertThat(view.llamaCppDefaultModel().value()).isEqualTo("override-llama-model");
        assertThat(view.llamaCppDefaultModel().source()).isEqualTo("override");
        // apiKey was not part of the second PUT, so the first override must still be effective
        assertThat(view.openrouterApiKeySet()).isTrue();
        assertThat(view.openrouterApiKeyMasked()).endsWith("-key");
    }

    @Test
    void putWithEmptyStringClearsOverrideAndFallsBackToEnv() {
        controller.put(new ConfigController.UpdateRequest("override-key", "override-or-model", "override-llama-model"));

        ConfigController.ConfigView cleared = controller.put(
                new ConfigController.UpdateRequest("", "", ""));

        assertThat(cleared.openrouterApiKeySet()).isTrue(); // env key is still present
        assertThat(cleared.openrouterApiKeyMasked()).endsWith(
                OPENROUTER.apiKey().substring(OPENROUTER.apiKey().length() - 4));
        assertThat(cleared.openrouterDefaultModel().value()).isEqualTo(OPENROUTER.defaultModel());
        assertThat(cleared.openrouterDefaultModel().source()).isEqualTo("env");
        assertThat(cleared.llamaCppDefaultModel().value()).isEqualTo(LLAMA_CPP.defaultModel());
        assertThat(cleared.llamaCppDefaultModel().source()).isEqualTo("env");
    }

    @Test
    void getReflectsOverriddenModelsWithOverrideSource() {
        controller.put(new ConfigController.UpdateRequest(null, "override-or-model", "override-llama-model"));

        ConfigController.ConfigView view = controller.get();

        assertThat(view.openrouterDefaultModel().value()).isEqualTo("override-or-model");
        assertThat(view.openrouterDefaultModel().source()).isEqualTo("override");
        assertThat(view.llamaCppDefaultModel().value()).isEqualTo("override-llama-model");
        assertThat(view.llamaCppDefaultModel().source()).isEqualTo("override");
    }
}
