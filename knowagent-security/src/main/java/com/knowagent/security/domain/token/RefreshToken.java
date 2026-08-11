package com.knowagent.security.domain.token;

import com.knowagent.common.tenant.TenantId;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RefreshToken(
        UUID id,
        TenantId tenantId,
        UUID userId,
        UUID familyId,
        UUID parentTokenId,
        String tokenHash,
        RefreshTokenStatus status,
        Instant issuedAt,
        Instant expiresAt,
        Instant consumedAt,
        Instant revokedAt,
        String revokeReason,
        InetAddress issuedIp,
        String userAgent,
        long version
) {
    public RefreshToken {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(familyId, "familyId must not be null");
        Objects.requireNonNull(tokenHash, "tokenHash must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(issuedAt, "issuedAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("expiresAt must be after issuedAt");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }

    public boolean belongsTo(TenantId expectedTenantId, UUID expectedUserId) {
        return tenantId.equals(Objects.requireNonNull(expectedTenantId, "expectedTenantId must not be null"))
                && userId.equals(Objects.requireNonNull(expectedUserId, "expectedUserId must not be null"));
    }

    @Override
    public String toString() {
        return "RefreshToken[id=" + id + ", tenantId=" + tenantId + ", userId=" + userId
                + ", familyId=" + familyId + ", status=" + status + ", version=" + version + "]";
    }
}
