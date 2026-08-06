package dev.giovane.llmproxy.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link AdminTokenInterceptor} only for {@code /v1/config/**}. Every other route
 * (/v1/chat/completions, /v1/embeddings, /health, /v1/models) stays open, matching the 4 existing
 * consumers of this proxy.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AdminTokenInterceptor adminTokenInterceptor;

    public WebMvcConfig(AdminTokenInterceptor adminTokenInterceptor) {
        this.adminTokenInterceptor = adminTokenInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminTokenInterceptor).addPathPatterns("/v1/config", "/v1/config/**");
    }
}
