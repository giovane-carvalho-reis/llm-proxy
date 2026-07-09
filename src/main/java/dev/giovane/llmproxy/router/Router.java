package dev.giovane.llmproxy.router;

/** Pure routing decision — no Spring, no IO, so it is trivially unit-testable. */
public final class Router {

    public static final String LLAMA = "llama-cpp";
    public static final String OPENROUTER = "openrouter";

    private Router() {
    }

    /**
     * Picks the upstream for a request.
     *
     * <p>Explicit route ("llama-cpp"/"openrouter") wins. In "auto": a request that
     * asks for speed and is small enough goes to OpenRouter (fast cloud API);
     * everything else stays on local llama.cpp (no per-token cost, big context).
     */
    public static String resolve(String route, boolean speed, int estTokens, int speedThreshold) {
        if (LLAMA.equals(route) || OPENROUTER.equals(route)) {
            return route;
        }
        if (speed && estTokens < speedThreshold) {
            return OPENROUTER;
        }
        return LLAMA;
    }
}
