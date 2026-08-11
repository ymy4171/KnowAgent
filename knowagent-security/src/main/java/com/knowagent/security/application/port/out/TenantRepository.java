package com.knowagent.security.application.port.out;

import com.knowagent.security.domain.tenant.Tenant;

import java.util.Optional;

public interface TenantRepository {
    Optional<Tenant> findActiveBySlug(String slug);
}
