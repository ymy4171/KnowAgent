package com.knowagent.api.bootstrap;

import com.knowagent.security.application.service.AdminBootstrap;
import com.knowagent.security.application.service.AdminBootstrapRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import java.util.Objects;

/**
 * Runs the developer-admin bootstrap after the application context is ready.
 *
 * <p>Behavior:
 * <ul>
 *   <li>When {@code bootstrap.enabled} is false the runner logs and returns; nothing
 *       is created.</li>
 *   <li>When enabled, the parameters are validated through
 *       {@link AdminBootstrapRequest}. Missing values or a password below the
 *       documented minimum length raise an {@link IllegalStateException} that aborts
 *       startup (fail fast), so a misconfigured environment never silently boots
 *       without an admin and never gets an auto-generated password.</li>
 *   <li>Messages never contain the raw password, and {@link AdminBootstrap} encodes
 *       it with Argon2 before persistence.</li>
 * </ul>
 */
public final class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final AdminBootstrapProperties properties;
    private final AdminBootstrap bootstrap;

    public AdminBootstrapRunner(AdminBootstrapProperties properties, AdminBootstrap bootstrap) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.bootstrap = Objects.requireNonNull(bootstrap, "bootstrap must not be null");
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.enabled()) {
            LOG.info("Admin bootstrap is disabled; skipping tenant/admin initialization.");
            return;
        }

        AdminBootstrapRequest request;
        try {
            request = new AdminBootstrapRequest(
                    properties.tenantSlug(),
                    properties.tenantName(),
                    properties.adminLogin(),
                    properties.adminDisplayName(),
                    properties.adminPassword());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Admin bootstrap is enabled but its configuration is invalid: " + exception.getMessage(),
                    exception);
        }

        bootstrap.initialize(request);
        LOG.info("Admin bootstrap finished for tenant '{}' and admin login '{}'.",
                request.tenantSlug(), request.adminLogin());
    }
}
