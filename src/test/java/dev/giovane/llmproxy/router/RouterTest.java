package dev.giovane.llmproxy.router;

import dev.giovane.llmproxy.api.InvalidRequestException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RouterTest {

    @Test
    void explicitRouteIsNormalizedForCaseAndSpace() {
        assertThat(Router.resolve("OpenRouter", false, 10, 100)).isEqualTo(Router.OPENROUTER);
        assertThat(Router.resolve(" LLAMA-CPP ", true, 10, 100)).isEqualTo(Router.LLAMA);
    }

    @Test
    void unknownRouteIsRejected() {
        assertThatThrownBy(() -> Router.resolve("xyz", false, 10, 100)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void autoWithSpeedAndSmallRequestGoesToOpenrouter() {
        assertThat(Router.resolve("auto", true, 10, 100)).isEqualTo(Router.OPENROUTER);
    }

    @Test
    void autoWithoutSpeedOrLargeRequestStaysLocal() {
        assertThat(Router.resolve("auto", false, 10, 100)).isEqualTo(Router.LLAMA);
        assertThat(Router.resolve("AUTO", true, 500, 100)).isEqualTo(Router.LLAMA);
    }

    @Test
    void nullRouteBehavesAsAuto() {
        assertThat(Router.resolve(null, true, 10, 100)).isEqualTo(Router.OPENROUTER);
    }
}
