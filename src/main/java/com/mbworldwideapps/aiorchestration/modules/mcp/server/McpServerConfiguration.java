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

    @Bean
    public FilterRegistrationBean<McpToolSurfaceFilter> mcpToolSurfaceFilterRegistration(
            com.mbworldwideapps.aiorchestration.config.McpToolSurfaceProperties tools,
            com.fasterxml.jackson.databind.ObjectMapper json, AiOrchestrationProperties properties) {
        FilterRegistrationBean<McpToolSurfaceFilter> registration =
                new FilterRegistrationBean<>(new McpToolSurfaceFilter(tools, json));
        registration.addUrlPatterns(properties.mcp().basePath(), properties.mcp().basePath() + "/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }
}
