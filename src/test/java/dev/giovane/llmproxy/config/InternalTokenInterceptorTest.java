package dev.giovane.llmproxy.config;

import dev.giovane.llmproxy.controller.ChatController;
import dev.giovane.llmproxy.controller.ConfigController;
import dev.giovane.llmproxy.service.ProxyService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SPEC-permissoes-admin-investidor D1: todo /v1/** exige X-Internal-Token; /health fica livre. */
@WebMvcTest(controllers = {ChatController.class, ConfigController.class})
@EnableConfigurationProperties(ProxyProperties.class)
@TestPropertySource(properties = "llm-proxy.admin-token=admin")
class InternalTokenInterceptorTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProxyService proxyService;

    @MockBean
    private LlmConfigState configState;

    @Test
    void usageRoutesRejectMissingOrWrongToken() throws Exception {
        mockMvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v1/models/chat").header("X-Internal-Token", "errado"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v1/config").header("X-Admin-Token", "admin"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void usageRoutesAcceptTheServiceToken() throws Exception {
        StreamingResponseBody empty = out -> { };
        when(proxyService.completions(anyString(), anyString(), any())).thenReturn(ResponseEntity.ok(empty));
        mockMvc.perform(post("/v1/chat/completions").header("X-Internal-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void healthStaysOpen() throws Exception {
        mockMvc.perform(get("/health")).andExpect(status().isOk());
    }

    @Test
    void blankTokenRefusesToStart() {
        assertThatThrownBy(() -> new InternalTokenInterceptor(" ")).isInstanceOf(IllegalStateException.class);
    }
}
