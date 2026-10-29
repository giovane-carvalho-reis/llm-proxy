package dev.giovane.llmproxy.controller;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.giovane.llmproxy.config.LlmConfigState;
import dev.giovane.llmproxy.config.ProxyProperties;
import dev.giovane.llmproxy.service.ProxyService;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** I1: malformed or non-object bodies reach the caller as 400 in the OpenAI error shape, not 500. */
class ApiExceptionHandlerTest {

    private final MockMvc mvc;

    ApiExceptionHandlerTest() {
        // Upstream never reached: parsing fails before any HTTP call.
        ProxyProperties.Upstream up = new ProxyProperties.Upstream("http://localhost:1", null, "m", null);
        ProxyProperties props = new ProxyProperties(up, up, up, up,
                new ProxyProperties.Routing(8000, Duration.ofMinutes(1)), "", List.of(), List.of());
        ProxyService service = new ProxyService(props, new LlmConfigState(), new ObjectMapper());
        mvc = MockMvcBuilders.standaloneSetup(new ChatController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void invalidBodiesReturn400InOpenAiShape() throws Exception {
        for (String path : List.of("/v1/chat/completions", "/v1/embeddings", "/v1/rerank")) {
            for (String body : List.of("[]", "\"x\"", "{")) {
                mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
                        .andExpect(jsonPath("$.error.message").isNotEmpty());
            }
        }
    }
}
