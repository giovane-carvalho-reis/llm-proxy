package dev.giovane.llmproxy.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.giovane.llmproxy.config.LlmConfigState;
import dev.giovane.llmproxy.config.ProxyProperties;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GuardrailVersionTest {

    @Test
    void hashChangesWhenTextChanges() {
        assertThat(ProxyService.sha256Hex("a")).isNotEqualTo(ProxyService.sha256Hex("b")).hasSize(64)
                .matches("[0-9a-f]+");
    }

    @Test
    void maintenanceCommentDoesNotChangeVersion() {
        String a = ProxyService.stripMaintenanceComment("<!--\nnota 1\n-->\n\nTexto.\n");
        String b = ProxyService.stripMaintenanceComment("  <!-- outra nota -->Texto.");
        assertThat(ProxyService.sha256Hex(a)).isEqualTo(ProxyService.sha256Hex(b));
    }

    @Test
    void serviceVersionMatchesShaOfLoadedFile() throws Exception {
        String raw = new String(new ClassPathResource("prompts/guardrails.md").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        ProxyProperties.Upstream up = new ProxyProperties.Upstream("http://localhost:1", null, "m", null);
        ProxyProperties props = new ProxyProperties(up, up, up, up,
                new ProxyProperties.Routing(8000, Duration.ofMinutes(1)), "", List.of(), List.of());
        ProxyService svc = TestServices.proxy(props, new LlmConfigState(), new ObjectMapper());
        assertThat(svc.guardrailVersion()).isEqualTo(ProxyService.sha256Hex(ProxyService.stripMaintenanceComment(raw)));
    }
}
