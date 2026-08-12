package com.knowagent.api.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the developer-admin bootstrap flow.
 *
 * <p>Bound from the {@code bootstrap.*} properties, which map to the environment
 * variables {@code BOOTSTRAP_ENABLED}, {@code BOOTSTRAP_TENANT_SLUG},
 * {@code BOOTSTRAP_TENANT_NAME}, {@code BOOTSTRAP_ADMIN_LOGIN},
 * {@code BOOTSTRAP_ADMIN_DISPLAY_NAME} and {@code BOOTSTRAP_ADMIN_PASSWORD}. The
 * password value is supplied at runtime only; it must never be written to
 * {@code .env.example}, commit history, logs or exceptions.
 *
 * <p>Initialization runs only when {@link #enabled()} is {@code true}. When enabled,
 * {@link AdminBootstrapRunner} rejects startup if any required value is missing or
 * the password is weaker than the documented policy; it never generates or prints a
 * password.
 *
 * @param enabled           set to {@code true} to run the bootstrap at startup
 * @param tenantSlug        slug of the tenant to create or reuse
 * @param tenantName        optional tenant display name; falls back to the slug
 * @param adminLogin        login of the admin user to create or reuse
 * @param adminDisplayName  optional admin display name; falls back to the login
 * @param adminPassword     raw bootstrap admin password, encoded with Argon2 on write
 */
@ConfigurationProperties(prefix = "bootstrap")
public record AdminBootstrapProperties(
        boolean enabled,
        String tenantSlug,
        String tenantName,
        String adminLogin,
        String adminDisplayName,
        String adminPassword
) {
    /**
     * Returns a string representation that never includes the raw admin password.
     * Configuration property values are routinely logged at startup through
     * {@code @ConfigurationProperties} binding diagnostics; this override ensures
     * the password is always displayed as {@code [REDACTED]} in logs, exception
     * messages and debugger output.
     */
    @Override
    public String toString() {
        return "AdminBootstrapProperties[enabled=" + enabled +
                ", tenantSlug=" + tenantSlug +
                ", tenantName=" + tenantName +
                ", adminLogin=" + adminLogin +
                ", adminDisplayName=" + adminDisplayName +
                ", adminPassword=[REDACTED]]";
    }
}
