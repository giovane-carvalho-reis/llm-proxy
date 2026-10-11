package dev.giovane.llmproxy.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Exige o token de serviço ({@code X-Internal-Token}) em toda rota {@code /v1/**}
 * (SPEC-permissoes-admin-investidor D1/D2). Sem {@code INTERNAL_TOKEN} o serviço não sobe:
 * a propriedade não tem default, então nunca abre por omissão de config. {@code /health} fica livre.
 */
@Component
public class InternalTokenInterceptor implements HandlerInterceptor {

    static final String HEADER = "X-Internal-Token";

    private final byte[] expected;

    public InternalTokenInterceptor(@Value("${INTERNAL_TOKEN}") String token) {
        if (token.isBlank()) {
            throw new IllegalStateException("INTERNAL_TOKEN vazio");
        }
        this.expected = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String provided = request.getHeader(HEADER);
        if (provided != null && MessageDigest.isEqual(expected, provided.getBytes(StandardCharsets.UTF_8))) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"message\":\"token interno invalido\"}");
        return false;
    }
}
