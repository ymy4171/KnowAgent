package it.contract;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Test-only configuration that turns on method-level authorization.
 *
 * <p>Production API code has no {@code @PreAuthorize} rules - every authenticated
 * request is allowed - so the 403 path of the security chain is otherwise
 * unreachable. This config enables {@code @PreAuthorize} on the probe controller's
 * admin-only endpoint, letting {@code AccessTokenSecurityIT} drive an
 * authenticated-but-forbidden request through the real filter chain and exercise
 * {@link com.knowagent.api.config.JsonAccessDeniedHandler}.
 */
@Configuration
@EnableMethodSecurity
public class MethodSecurityTestConfig {
}
