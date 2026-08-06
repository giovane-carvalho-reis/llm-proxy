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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fail-closed check: an empty {@code llm-proxy.admin-token} must deny /v1/config/** access
 * regardless of the header supplied, never open it by omission of config.
 */
@WebMvcTest(controllers = {ChatController.class, ConfigController.class})
@EnableConfigurationProperties(ProxyProperties.class)
@TestPropertySource(properties = "llm-proxy.admin-token=")
class AdminTokenInterceptorEmptyTokenTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProxyService proxyService;

    @MockBean
    private LlmConfigState configState;

    @Test
    void emptyAdminTokenDeniesAccessEvenWithAnyHeader() throws Exception {
        mockMvc.perform(put("/v1/config")
                        .header("X-Admin-Token", "anything")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }
}
