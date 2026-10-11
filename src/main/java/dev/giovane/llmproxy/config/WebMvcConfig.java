package dev.giovane.llmproxy.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * {@link InternalTokenInterceptor} guards every {@code /v1/**} route (only {@code /health} stays open);
 * {@link AdminTokenInterceptor} additionally guards {@code /v1/config/**}.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final InternalTokenInterceptor internalTokenInterceptor;
    private final AdminTokenInterceptor adminTokenInterceptor;

    public WebMvcConfig(InternalTokenInterceptor internalTokenInterceptor, AdminTokenInterceptor adminTokenInterceptor) {
        this.internalTokenInterceptor = internalTokenInterceptor;
        this.adminTokenInterceptor = adminTokenInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(internalTokenInterceptor).addPathPatterns("/v1/**");
        registry.addInterceptor(adminTokenInterceptor).addPathPatterns("/v1/config", "/v1/config/**");
    }
}
