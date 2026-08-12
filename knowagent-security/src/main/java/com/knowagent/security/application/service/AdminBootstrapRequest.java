package com.knowagent.security.application.service;

import java.util.Locale;

/**
 * Validated parameters for the developer-admin bootstrap flow.
 *
 * <p>The compact constructor normalizes and validates every value, so the
 * {@link AdminBootstrapService} can rely on well-formed input:
 * <ul>
 *   <li>blank values are rejected (no auto-generated defaults, no printed
 *       passwords),</li>
 *   <li>the tenant slug and admin login are normalized to lowercase to satisfy the
 *       {@code tenants.slug} and {@code users.login_name} database CHECK
 *       constraints,</li>
 *   <li>the tenant name and admin display name fall back to the slug and login
 *       respectively,</li>
 *   <li>the admin password must meet the documented minimum length so a weak,
 *       brute-forceable bootstrap credential is rejected at startup.</li>
 * </ul>
 *
 * @param tenantName      optional tenant display name; falls back to the slug
 * @param adminDisplayName optional admin display name; falls back to the login
 */
public record AdminBootstrapRequest(
        String tenantSlug,
        String tenantName,
        String adminLogin,
        String adminDisplayName,
        String adminPassword
) {

    /** Minimum length for the bootstrap admin password. */
    public static final int MIN_ADMIN_PASSWORD_LENGTH = 12;

    public AdminBootstrapRequest {
        tenantSlug = clean(tenantSlug, "bootstrap tenant slug").toLowerCase(Locale.ROOT);
        tenantName = cleanOr(tenantName, tenantSlug);
        adminLogin = clean(adminLogin, "bootstrap admin login").toLowerCase(Locale.ROOT);
        adminDisplayName = cleanOr(adminDisplayName, adminLogin);
        adminPassword = clean(adminPassword, "bootstrap admin password");

        if (!tenantSlug.matches("^[a-z0-9][a-z0-9-]{0,62}$") || tenantSlug.endsWith("-")) {
            throw new IllegalArgumentException(
                    "bootstrap tenant slug must match ^[a-z0-9][a-z0-9-]{0,62}$ and not end with '-'");
        }
        if (adminLogin.length() > 128) {
            throw new IllegalArgumentException("bootstrap admin login must be at most 128 characters");
        }
        if (adminPassword.length() < MIN_ADMIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "bootstrap admin password must be at least " + MIN_ADMIN_PASSWORD_LENGTH + " characters");
        }
    }

    private static String clean(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.trim();
    }

    private static String cleanOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /**
     * Returns a string representation that never includes the raw admin password.
     * The default record {@code toString()} would emit every component value
     * verbatim; this override ensures the password is always displayed as
     * {@code [REDACTED]} in logs, exception messages and debugger output.
     */
    @Override
    public String toString() {
        return "AdminBootstrapRequest[tenantSlug=" + tenantSlug +
                ", tenantName=" + tenantName +
                ", adminLogin=" + adminLogin +
                ", adminDisplayName=" + adminDisplayName +
                ", adminPassword=[REDACTED]]";
    }
}
