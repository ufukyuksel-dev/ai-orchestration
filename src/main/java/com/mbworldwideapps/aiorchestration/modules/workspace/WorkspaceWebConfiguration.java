package com.mbworldwideapps.aiorchestration.modules.workspace;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WorkspaceWebConfiguration implements WebMvcConfigurer {
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // The panel is the product's single page: `/` shows it without a redirect hop.
        registry.addViewController("/").setViewName("forward:/workspace/index.html");
        registry.addRedirectViewController("/workspace", "/workspace/index.html");
        registry.addRedirectViewController("/workspace/", "/workspace/index.html");
    }
}
