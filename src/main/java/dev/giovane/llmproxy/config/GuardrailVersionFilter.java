package dev.giovane.llmproxy.config;

import dev.giovane.llmproxy.service.ProxyService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Stamps {@code X-Guardrail-Version} on every response of {@code POST /v1/chat/completions} (streaming,
 * non-streaming and error responses). Set before the chain so it is present even when the body is streamed.
 */
@Component
public class GuardrailVersionFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Guardrail-Version";
    private static final String PATH = "/v1/chat/completions";

    private final ProxyService service;

    public GuardrailVersionFilter(ProxyService service) {
        this.service = service;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !PATH.equals(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(HEADER, service.guardrailVersion());
        chain.doFilter(request, response);
    }
}
