package com.knowagent.security.domain.user;

import com.knowagent.common.tenant.TenantId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record User(
        UUID id,
        TenantId tenantId,
        UUID departmentId,
        String loginName,
        String displayName,
        String email,
        String phoneNumber,
        String avatarObjectKey,
        String passwordHash,
        UserStatus status,
        int loginFailedCount,
        Instant lastFailedLoginAt,
        Instant loginLockedUntil,
        Instant lastLoginAt,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
    public User {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(loginName, "loginName must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(passwordHash, "passwordHash must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (loginFailedCount < 0 || version < 0) {
            throw new IllegalArgumentException("counts and version must not be negative");
        }
    }

    @Override
    public String toString() {
        return "User[id=" + id + ", tenantId=" + tenantId + ", loginName=" + loginName
                + ", status=" + status + ", version=" + version + "]";
    }
}
