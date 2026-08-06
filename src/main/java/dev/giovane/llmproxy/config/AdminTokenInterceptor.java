package dev.giovane.llmproxy.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Guards {@code /v1/config/**} with a shared secret ({@code X-Admin-Token} header). Fail-closed:
 * an empty/absent {@code llm-proxy.admin-token} denies every request, never opens access by
 * omission of config.
 */
@Component
public class AdminTokenInterceptor implements HandlerInterceptor {

    private static final String HEADER = "X-Admin-Token";

    private final ProxyProperties props;

    public AdminTokenInterceptor(ProxyProperties props) {
        this.props = props;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String expected = props.adminToken();
        String provided = request.getHeader(HEADER);
        if (expected == null || expected.isBlank() || provided == null
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                        provided.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
        return true;
    }
}
