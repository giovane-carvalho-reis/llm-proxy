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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies {@link AdminTokenInterceptor}, registered via {@link WebMvcConfig}, guards
 * {@code /v1/config/**} while leaving {@code /v1/chat/completions} open to the existing
 * consumers (regression check).
 */
@WebMvcTest(controllers = {ChatController.class, ConfigController.class})
@EnableConfigurationProperties(ProxyProperties.class)
@TestPropertySource(properties = "llm-proxy.admin-token=correct-token")
class AdminTokenInterceptorTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProxyService proxyService;

    @MockBean
    private LlmConfigState configState;

    @Test
    void putConfigWithoutHeaderIsForbidden() throws Exception {
        mockMvc.perform(put("/v1/config").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void putConfigWithWrongHeaderIsForbidden() throws Exception {
        mockMvc.perform(put("/v1/config")
                        .header("X-Admin-Token", "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void putConfigWithCorrectHeaderIsAllowed() throws Exception {
        mockMvc.perform(put("/v1/config")
                        .header("X-Admin-Token", "correct-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void chatCompletionsWorksWithoutAnyAdminHeader() throws Exception {
        when(proxyService.completions(anyString(), anyString(), any()))
                .thenReturn(ResponseEntity.ok("{}"));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void getConfigIsAlsoForbiddenWithoutHeader() throws Exception {
        mockMvc.perform(get("/v1/config")).andExpect(status().isForbidden());
    }
}
