package com.knowagent.security.infrastructure.persistence.repository;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.security.application.port.out.RefreshTokenStore;
import com.knowagent.security.domain.token.RefreshToken;
import com.knowagent.security.infrastructure.persistence.converter.IdentityPersistenceConverter;
import com.knowagent.security.infrastructure.persistence.entity.RefreshTokenPo;
import com.knowagent.security.infrastructure.persistence.mapper.RefreshTokenMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MyBatisRefreshTokenStore implements RefreshTokenStore {
    private final RefreshTokenMapper mapper;

    public MyBatisRefreshTokenStore(RefreshTokenMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    public Optional<RefreshToken> findByTokenHash(String tokenHash) {
        requireHash(tokenHash);
        RefreshTokenPo record = mapper.selectByTokenHash(tokenHash);
        return Optional.ofNullable(record).map(IdentityPersistenceConverter::toDomain);
    }

    @Override
    public Optional<RefreshToken> findById(TenantId tenantId, UUID tokenId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(tokenId, "tokenId must not be null");
        RefreshTokenPo record = mapper.selectByIdAndTenant(tenantId.value(), tokenId);
        return Optional.ofNullable(record).map(IdentityPersistenceConverter::toDomain);
    }

    @Override
    public Optional<RefreshToken> findFamilyRootForUpdate(TenantId tenantId, UUID familyId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(familyId, "familyId must not be null");
        RefreshTokenPo record = mapper.selectFamilyRootForUpdate(tenantId.value(), familyId);
        return Optional.ofNullable(record).map(IdentityPersistenceConverter::toDomain);
    }

    @Override
    public void insert(RefreshToken token) {
        Objects.requireNonNull(token, "token must not be null");
        // Runs during login, before authentication exists. The PO carries tenantId
        // explicitly, so the tenant-line interceptor trusts it and never consults
        // TenantContext (the same rule the bootstrap writes rely on).
        insertPo(token);
    }

    /**
     * The rotation inserts its child inside a savepoint so a PostgreSQL unique
     * violation on {@code uq_refresh_tokens_one_child} rolls back only this statement;
     * without the savepoint the aborted transaction would reject the family
     * revocation that follows. Only the SHA-256 hash ever reaches the store.
     */
    @Override
    @Transactional(propagation = Propagation.NESTED)
    public void insertChild(RefreshToken token) {
        Objects.requireNonNull(token, "token must not be null");
        insertPo(token);
    }

    private void insertPo(RefreshToken token) {
        int inserted = mapper.insert(IdentityPersistenceConverter.toPersistence(token));
        if (inserted != 1) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Unable to persist refresh token");
        }
    }

    @Override
    public boolean consume(RefreshToken token, Instant consumedAt) {
        Objects.requireNonNull(token, "token must not be null");
        Objects.requireNonNull(consumedAt, "consumedAt must not be null");
        return mapper.consumeActive(token.tenantId().value(), token.id(), consumedAt) == 1;
    }

    @Override
    public int revokeFamily(TenantId tenantId, UUID familyId, Instant revokedAt, String reason) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(familyId, "familyId must not be null");
        Objects.requireNonNull(revokedAt, "revokedAt must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        return mapper.revokeActiveFamily(tenantId.value(), familyId, revokedAt, reason);
    }

    private static void requireHash(String tokenHash) {
        Objects.requireNonNull(tokenHash, "tokenHash must not be null");
        if (!tokenHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("tokenHash must be a lowercase SHA-256 hexadecimal value");
        }
    }
}
