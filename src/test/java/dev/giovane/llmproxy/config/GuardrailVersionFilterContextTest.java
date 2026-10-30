package dev.giovane.llmproxy.config;

import dev.giovane.llmproxy.controller.ChatController;
import dev.giovane.llmproxy.service.ModelCatalogService;
import dev.giovane.llmproxy.service.ProxyService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** O filtro está registrado de verdade no contexto: até a resposta 401 do interceptor leva o header. */
@WebMvcTest(controllers = ChatController.class)
@EnableConfigurationProperties(ProxyProperties.class)
class GuardrailVersionFilterContextTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProxyService proxyService;

    @MockBean
    private ModelCatalogService modelCatalogService;

    @MockBean
    private LlmConfigState configState;

    @Test
    void filterIsRegisteredInContext() throws Exception {
        when(proxyService.guardrailVersion()).thenReturn("abc123");
        mockMvc.perform(post("/v1/chat/completions").servletPath("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Guardrail-Version", "abc123"));
    }
}
