package com.jisuodashi.rbac;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class RbacWebConfig implements WebMvcConfigurer {

    private final StoreScopeInterceptor storeScopeInterceptor;

    public RbacWebConfig(StoreScopeInterceptor storeScopeInterceptor) {
        this.storeScopeInterceptor = storeScopeInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(storeScopeInterceptor)
                .addPathPatterns("/api/v1/f/**", "/api/v1/a/**");
    }
}
