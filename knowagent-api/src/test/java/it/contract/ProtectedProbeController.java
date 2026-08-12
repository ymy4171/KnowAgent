package it.contract;

import com.knowagent.security.context.TenantContext;
import com.knowagent.security.principal.TenantPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Test-only protected endpoint used by {@code AccessTokenSecurityIT}.
 *
 * <p>It lives in a package outside {@code com.knowagent} so the api component scan
 * never registers it in production or in the other integration tests; that IT adds
 * it explicitly as a source. It echoes the authenticated principal, the granted
 * authorities and whether {@link TenantContext} was populated, proving end-to-end
 * that a valid token reaches a protected route and establishes the tenant context.
 *
 * <p>The {@code /admin} route is guarded by {@code @PreAuthorize} (enabled in the
 * test context by {@link MethodSecurityTestConfig}) so the integration test can
 * drive an authenticated-but-forbidden request and verify the JSON 403 written by
 * {@code JsonAccessDeniedHandler}.
 */
@RestController
@RequestMapping("/api/v1/probe")
public class ProtectedProbeController {

    @GetMapping
    public Map<String, Object> probe() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        TenantPrincipal principal = (TenantPrincipal) authentication.getPrincipal();
        return Map.of(
                "userId", principal.userId(),
                "tenantId", principal.tenantId().value(),
                "roles", principal.roles(),
                "authorities", authentication.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .sorted()
                        .toList(),
                "tenantContextPresent", TenantContext.isSet());
    }

    @GetMapping("/admin")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> adminProbe() {
        return probe();
    }
}
