package dev.giovane.llmproxy.config;

import dev.giovane.llmproxy.router.Router;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmConfigStateTest {

    private static final ProxyProperties.Upstream BASE =
            new ProxyProperties.Upstream("https://openrouter.ai/api", "env-key", "env-model", null);

    @Test
    void effectiveReturnsBaseUnchangedWhenNoOverrideIsSet() {
        LlmConfigState state = new LlmConfigState();

        ProxyProperties.Upstream result = state.effective(Router.OPENROUTER, BASE);

        assertThat(result).isSameAs(BASE);
    }

    @Test
    void effectiveReturnsOverriddenValuesWhenSet() {
        LlmConfigState state = new LlmConfigState();
        state.setOpenrouterApiKey("override-key");
        state.setOpenrouterDefaultModel("override-model");

        ProxyProperties.Upstream result = state.effective(Router.OPENROUTER, BASE);

        assertThat(result.apiKey()).isEqualTo("override-key");
        assertThat(result.defaultModel()).isEqualTo("override-model");
        assertThat(result.baseUrl()).isEqualTo(BASE.baseUrl());
    }

    @Test
    void settingEmptyStringClearsOverrideAndFallsBackToBase() {
        LlmConfigState state = new LlmConfigState();
        state.setOpenrouterApiKey("override-key");

        state.setOpenrouterApiKey("");

        ProxyProperties.Upstream result = state.effective(Router.OPENROUTER, BASE);
        assertThat(result.apiKey()).isEqualTo(BASE.apiKey());
    }

    @Test
    void llamaCppOverridesDoNotAffectOpenrouterTarget() {
        LlmConfigState state = new LlmConfigState();
        state.setLlamaCppDefaultModel("local-override");

        ProxyProperties.Upstream result = state.effective(Router.OPENROUTER, BASE);

        assertThat(result).isSameAs(BASE);
    }
}
