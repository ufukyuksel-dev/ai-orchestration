package com.mbworldwideapps.aiorchestration.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.mbworldwideapps.aiorchestration.core.policy.PolicyProperties;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdminTokenFilterTest {

    @Test
    void rejectsAdminRequestWithoutConfiguredHeader() throws ServletException, IOException {
        AdminTokenFilter filter = new AdminTokenFilter(new PolicyProperties(true, true, "admin-secret", List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/acl/sync");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void allowsAdminRequestWithMatchingHeader() throws ServletException, IOException {
        AdminTokenFilter filter = new AdminTokenFilter(new PolicyProperties(true, true, "admin-secret", List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/acl/sync");
        request.addHeader(AdminTokenFilter.ADMIN_TOKEN_HEADER, "admin-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void doesNotGuardNonAdminRoutes() throws ServletException, IOException {
        AdminTokenFilter filter = new AdminTokenFilter(new PolicyProperties(true, true, "admin-secret", List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/memory/capture");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }
}
