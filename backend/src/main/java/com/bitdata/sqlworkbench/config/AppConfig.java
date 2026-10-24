package com.bitdata.sqlworkbench.config;

import com.bitdata.sqlworkbench.security.AuthTokenInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AppConfig {

    private final AuthTokenInterceptor authTokenInterceptor;

    public AppConfig(AuthTokenInterceptor authTokenInterceptor) {
        this.authTokenInterceptor = authTokenInterceptor;
    }

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOriginPatterns("file://", "http://localhost:*")
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("Content-Type", "X-Auth-Token")
                        .allowCredentials(false);
            }
        };
    }

    @Bean
    public WebMvcConfigurer authInterceptorConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(authTokenInterceptor)
                    .addPathPatterns("/api/**")
                    // The launcher's readiness poll and the renderer's status
                    // badge hit /api/health without a token (health reveals nothing).
                    .excludePathPatterns("/api/health");
            }
        };
    }
}
