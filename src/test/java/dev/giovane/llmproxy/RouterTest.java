package dev.giovane.llmproxy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Routing rules — the only non-trivial logic in the proxy. */
class RouterTest {

    @Test
    void explicitRouteAlwaysWins() {
        assertThat(Router.resolve("openrouter", false, 999_999, 8000)).isEqualTo(Router.OPENROUTER);
        assertThat(Router.resolve("llama-cpp", true, 1, 8000)).isEqualTo(Router.LLAMA);
    }

    @Test
    void autoSendsSmallSpeedRequestsToOpenRouter() {
        assertThat(Router.resolve("auto", true, 500, 8000)).isEqualTo(Router.OPENROUTER);
    }

    @Test
    void autoKeepsBigOrNonSpeedRequestsLocal() {
        assertThat(Router.resolve("auto", true, 50_000, 8000)).isEqualTo(Router.LLAMA);   // too big for cloud
        assertThat(Router.resolve("auto", false, 100, 8000)).isEqualTo(Router.LLAMA);     // no speed hint
    }
}
