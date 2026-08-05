package com.knowagent.security.principal;

import com.knowagent.common.tenant.TenantId;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record TenantPrincipal(
        TenantId tenantId,
        UUID userId,
        Set<String> roles
) {

    public TenantPrincipal {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        roles = Set.copyOf(roles);
    }
}

