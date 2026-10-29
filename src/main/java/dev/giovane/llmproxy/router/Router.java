package dev.giovane.llmproxy.router;

import dev.giovane.llmproxy.api.InvalidRequestException;

import java.util.Locale;

/** Pure routing decision — no Spring, no IO, so it is trivially unit-testable. */
public final class Router {

    public static final String LLAMA = "llama-cpp";
    public static final String OPENROUTER = "openrouter";
    public static final String AUTO = "auto";

    private Router() {
    }

    /**
     * Picks the upstream for a request.
     *
     * <p>Explicit route ("llama-cpp"/"openrouter", case/space-insensitive) wins; any other value except "auto" is rejected
     * with {@link InvalidRequestException}. In "auto": a request that
     * asks for speed and is small enough goes to OpenRouter (fast cloud API);
     * everything else stays on local llama.cpp (no per-token cost, big context).
     */
    public static String resolve(String route, boolean speed, int estTokens, int speedThreshold) {
        String normalized = route == null ? AUTO : route.strip().toLowerCase(Locale.ROOT);
        if (LLAMA.equals(normalized) || OPENROUTER.equals(normalized)) {
            return normalized;
        }
        if (!AUTO.equals(normalized)) {
            String shown = route.length() > 40 ? route.substring(0, 40) + "..." : route;
            throw new InvalidRequestException("invalid route '" + shown + "': expected llama-cpp, openrouter or auto");
        }
        if (speed && estTokens < speedThreshold) {
            return OPENROUTER;
        }
        return LLAMA;
    }
}
