package com.knowagent.api.config;

import com.knowagent.common.tenant.TenantId;
import com.knowagent.security.context.TenantContext;
import com.knowagent.security.principal.TenantPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantContextFilterTest {

    private static final UUID TENANT_A_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final TenantContextFilter filter = new TenantContextFilter();

    @BeforeEach
    void resetSecurityContext() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @AfterEach
    void resetContextsAfterEach() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void setsContextFromAuthenticatedPrincipalAndClearsIt() throws Exception {
        authenticateAs(principal(TENANT_A_ID));

        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request(), response(), chain);

        assertThat(chain.getRequest()).isNotNull();
        // Context must already be gone once the filter returned.
        assertThat(TenantContext.isSet()).isFalse();
        assertThat(TenantContext.getPrincipal()).isEmpty();
    }

    @Test
    void sequentialRequestsOnReusedThreadNeverLeakTenant() throws Exception {
        authenticateAs(principal(TENANT_A_ID));
        CapturingChain first = new CapturingChain();
        filter.doFilter(request(), response(), first);
        assertThat(first.tenantDuringChain()).isEqualTo(TENANT_A_ID);
        assertThat(TenantContext.isSet()).isFalse();

        authenticateAs(principal(TENANT_B_ID));
        CapturingChain second = new CapturingChain();
        filter.doFilter(request(), response(), second);
        assertThat(second.tenantDuringChain()).isEqualTo(TENANT_B_ID);
        assertThat(TenantContext.isSet()).isFalse();
    }

    @Test
    void unauthenticatedRequestLeavesContextEmpty() throws Exception {
        SecurityContextHolder.clearContext();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request(), response(), chain);
        assertThat(TenantContext.isSet()).isFalse();
    }

    @Test
    void staleTenantFromPreviousRequestIsClearedBeforeAnonymousRequest() throws Exception {
        // Simulate a thread that still carries tenant-A from a prior request.
        TenantContext.set(principal(TENANT_A_ID));

        // This request is anonymous, so the filter has no principal to install.
        SecurityContextHolder.clearContext();
        CapturingChain chain = new CapturingChain();
        filter.doFilter(request(), response(), chain);

        // Downstream business code must never observe the stale tenant-A.
        assertThat(chain.tenantDuringChain()).isNull();
        assertThat(TenantContext.isSet()).isFalse();
    }

    @Test
    void clientHeaderIsNeverTrustedForTenant() throws Exception {
        // No authenticated principal; a crafted X-Tenant-Id header must be ignored.
        MockHttpServletRequest forged = request();
        forged.addHeader("X-Tenant-Id", TENANT_B_ID.toString());

        CapturingChain chain = new CapturingChain();
        filter.doFilter(forged, response(), chain);

        assertThat(chain.tenantDuringChain()).isNull();
        assertThat(TenantContext.isSet()).isFalse();
    }

    @Test
    void contextIsClearedEvenWhenDownstreamThrows() {
        authenticateAs(principal(TENANT_A_ID));

        assertThatThrownBy(() -> filter.doFilter(
                request(), response(), (req, res) -> {
                    throw new IllegalStateException("downstream failure");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("downstream failure");

        assertThat(TenantContext.isSet()).isFalse();
    }

    private static void authenticateAs(TenantPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private static TenantPrincipal principal(UUID tenantId) {
        return new TenantPrincipal(TenantId.of(tenantId), UUID.randomUUID(), Set.of("ROLE_USER"), Set.of());
    }

    private static MockHttpServletRequest request() {
        return new MockHttpServletRequest("GET", "/api/v1/system/info");
    }

    private static MockHttpServletResponse response() {
        return new MockHttpServletResponse();
    }

    /** Records the tenant visible to the downstream chain before the filter clears it. */
    private static final class CapturingChain implements FilterChain {
        private UUID observedTenant;

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) {
            observedTenant = TenantContext.getPrincipal()
                    .map(p -> p.tenantId().value())
                    .orElse(null);
        }

        UUID tenantDuringChain() {
            return observedTenant;
        }
    }
}
