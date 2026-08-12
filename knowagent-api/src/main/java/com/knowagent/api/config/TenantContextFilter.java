package com.knowagent.api.config;

import com.knowagent.security.context.TenantContext;
import com.knowagent.security.principal.TenantPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Populates {@link TenantContext} from the authenticated principal and always
 * clears it in a {@code finally} block.
 *
 * <p>The tenant is derived only from {@link Authentication#getPrincipal()} when it
 * is a {@link TenantPrincipal}. Arbitrary client headers such as {@code X-Tenant-Id}
 * are never trusted, so a caller cannot impersonate another tenant by header
 * injection.
 *
 * <p>Because the context is cleared in {@code finally}, a Servlet thread that is
 * reused for the next request can never observe the previous request's tenant,
 * and an exception mid-request still leaves the thread clean. The filter also
 * clears on entry, so a stale tenant left on the thread by a previous request is
 * removed before this request's business code runs, even when this request is
 * anonymous and therefore has no principal to set.
 *
 * <p>This filter is constructed inline by {@link SecurityBootstrapConfiguration} and
 * registered before {@code AuthorizationFilter} inside the Spring Security chain so
 * tenant-aware authorization can read {@link TenantContext} and so even an
 * authorization-rejected request passes through this filter's cleanup. It is
 * deliberately <em>not</em> a Spring bean, so Spring Boot does not auto-register it
 * a second time on the plain Servlet filter chain.
 */
public final class TenantContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        TenantContext.clear();
        TenantPrincipal principal = resolveAuthenticatedTenant();
        try {
            if (principal != null) {
                TenantContext.set(principal);
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private static TenantPrincipal resolveAuthenticatedTenant() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        return authentication.getPrincipal() instanceof TenantPrincipal principal ? principal : null;
    }
}
