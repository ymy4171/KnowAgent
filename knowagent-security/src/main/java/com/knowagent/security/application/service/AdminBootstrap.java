package com.knowagent.security.application.service;

/**
 * Inbound port for the developer-admin bootstrap flow.
 *
 * <p>The entry point that idempotently initializes a tenant, the ADMIN system role,
 * the admin user and their role binding. Implemented by
 * {@link AdminBootstrapService}; consumed by the startup runner in
 * {@code knowagent-api} (and test doubles).
 */
public interface AdminBootstrap {

    /**
     * Runs the bootstrap. Idempotent: repeated calls reuse existing rows and never
     * create duplicates. Runs in a single transaction.
     *
     * @param request validated bootstrap parameters; must not be {@code null}
     */
    void initialize(AdminBootstrapRequest request);
}
