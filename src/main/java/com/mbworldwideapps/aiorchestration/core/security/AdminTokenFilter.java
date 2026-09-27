package com.mbworldwideapps.aiorchestration.core.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import com.mbworldwideapps.aiorchestration.core.policy.PolicyProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminTokenFilter extends OncePerRequestFilter {

    static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";

    private final PolicyProperties properties;

    public AdminTokenFilter(PolicyProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!requiresAdminToken(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (properties.adminToken().isBlank()) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Admin token is not configured");
            return;
        }

        String providedToken = request.getHeader(ADMIN_TOKEN_HEADER);
        if (!constantTimeEquals(properties.adminToken(), providedToken)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid admin token");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean requiresAdminToken(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path == null || path.isBlank()) {
            path = request.getRequestURI();
        }
        return properties.adminTokenRequired() && path.startsWith("/api/admin/");
    }

    private static boolean constantTimeEquals(String expected, String provided) {
        if (provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }
}
