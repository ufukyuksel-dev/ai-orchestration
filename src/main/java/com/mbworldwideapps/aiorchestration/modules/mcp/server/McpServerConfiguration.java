package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import com.mbworldwideapps.aiorchestration.config.AiOrchestrationProperties;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
@ConditionalOnProperty(
        prefix = "ai-orchestration.mcp",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class McpServerConfiguration {

    @Bean
    public FilterRegistrationBean<McpAuthenticationFilter> mcpAuthFilterRegistration(
            McpAuthenticationFilter filter, AiOrchestrationProperties properties) {
        FilterRegistrationBean<McpAuthenticationFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns(
                properties.mcp().basePath(),
                properties.mcp().basePath() + "/*",
                "/workspace/api/*",
                "/terminal/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
