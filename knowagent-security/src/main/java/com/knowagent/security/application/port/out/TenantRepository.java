package com.knowagent.security.application.port.out;

import com.knowagent.common.tenant.TenantId;
import com.knowagent.security.domain.tenant.Tenant;

import java.util.Optional;

public interface TenantRepository {

    /** Finds an ACTIVE, non-deleted tenant by its normalized slug. */
    Optional<Tenant> findActiveBySlug(String slug);

    /** Finds an ACTIVE, non-deleted tenant by its id. */
    Optional<Tenant> findActiveById(TenantId tenantId);
}
