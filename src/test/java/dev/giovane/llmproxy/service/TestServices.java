package dev.giovane.llmproxy.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.giovane.llmproxy.config.LlmConfigState;
import dev.giovane.llmproxy.config.ProxyProperties;

/** Wires the service graph by hand for tests (no Spring context). */
public final class TestServices {

    private TestServices() { }

    public static ProxyService proxy(ProxyProperties props, LlmConfigState state, ObjectMapper mapper) {
        UpstreamHttp http = new UpstreamHttp(props);
        RequestLogger requestLog = new RequestLogger();
        return new ProxyService(props, state, mapper, http, new OpenRouterPayloadCustomizer(props, mapper),
                requestLog, new SseRelay(http, requestLog, mapper));
    }

    public static ModelCatalogService catalog(ProxyProperties props, ObjectMapper mapper) {
        return new ModelCatalogService(props, mapper, new UpstreamHttp(props), new RequestLogger());
    }
}
