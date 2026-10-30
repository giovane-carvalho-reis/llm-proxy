package dev.giovane.llmproxy.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import dev.giovane.llmproxy.config.GuardrailVersionFilter;
import dev.giovane.llmproxy.config.LlmConfigState;
import dev.giovane.llmproxy.config.ProxyProperties;
import dev.giovane.llmproxy.service.ProxyService;
import dev.giovane.llmproxy.service.TestServices;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SPEC-debitos-tecnicos F7a/D6: versão do guardrail em /guardrail-version e no header de completions. */
class GuardrailVersionHeaderTest {

    private HttpServer upstream;
    private MockMvc mvc;
    private ProxyService service;

    @BeforeEach
    void setUp() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.createContext("/v1/chat/completions", exchange -> {
            boolean stream = new String(exchange.getRequestBody().readAllBytes()).contains("\"stream\":true");
            byte[] out = (stream ? "data: {\"choices\":[]}\n\ndata: [DONE]\n\n" : "{\"ok\":true}").getBytes();
            exchange.getResponseHeaders().add("Content-Type", stream ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        upstream.start();
        String base = "http://localhost:" + upstream.getAddress().getPort();
        ProxyProperties.Upstream up = new ProxyProperties.Upstream(base, null, "m", null);
        ProxyProperties props = new ProxyProperties(up, up, up, up,
                new ProxyProperties.Routing(8000, Duration.ofMinutes(1)), "", List.of(), List.of());
        ObjectMapper mapper = new ObjectMapper();
        service = TestServices.proxy(props, new LlmConfigState(), mapper);
        mvc = MockMvcBuilders.standaloneSetup(new ChatController(service, TestServices.catalog(props, mapper)))
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new GuardrailVersionFilter(service))
                .build();
    }

    @AfterEach
    void tearDown() {
        upstream.stop(0);
    }

    @Test
    void endpointReturnsVersion() throws Exception {
        mvc.perform(get("/guardrail-version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(service.guardrailVersion()));
    }

    @Test
    void headerOnNonStreamingCompletions() throws Exception {
        MvcResult r = mvc.perform(post("/v1/chat/completions").servletPath("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"oi\"}]}"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(r)).andExpect(status().isOk())
                .andExpect(header().string("X-Guardrail-Version", service.guardrailVersion()));
        org.assertj.core.api.Assertions.assertThat(r.getResponse().getHeaderValues("X-Guardrail-Version")).hasSize(1);
    }

    @Test
    void headerOnStreamingCompletions() throws Exception {
        MvcResult r = mvc.perform(post("/v1/chat/completions").servletPath("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"m\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"oi\"}]}"))
                .andReturn();
        if (r.getRequest().isAsyncStarted()) {
            r = mvc.perform(asyncDispatch(r)).andReturn();
        }
        org.assertj.core.api.Assertions.assertThat(r.getResponse().getHeaderValues("X-Guardrail-Version"))
                .containsExactly(service.guardrailVersion());
    }

    @Test
    void headerOnErrorResponse() throws Exception {
        mvc.perform(post("/v1/chat/completions").servletPath("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Guardrail-Version", service.guardrailVersion()));
    }

    @Test
    void otherPathsDoNotCarryHeader() throws Exception {
        mvc.perform(get("/guardrail-version").servletPath("/guardrail-version")).andExpect(header().doesNotExist("X-Guardrail-Version"));
    }
}
