package com.knowagent.security.domain.role;

import com.knowagent.common.tenant.TenantId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record UserRole(
        UUID id,
        TenantId tenantId,
        UUID userId,
        UUID roleId,
        UUID grantedBy,
        Instant grantedAt,
        Instant expiresAt
) {
    public UserRole {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(roleId, "roleId must not be null");
        Objects.requireNonNull(grantedAt, "grantedAt must not be null");
        if (expiresAt != null && !expiresAt.isAfter(grantedAt)) {
            throw new IllegalArgumentException("expiresAt must be after grantedAt");
        }
    }

    public boolean isEffectiveAt(Instant instant) {
        Objects.requireNonNull(instant, "instant must not be null");
        return expiresAt == null || expiresAt.isAfter(instant);
    }
}
