package com.knowagent.security.application.port.out;

import com.knowagent.common.tenant.TenantId;
import com.knowagent.security.domain.token.RefreshToken;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenStore {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Re-reads one token by primary key, explicitly scoped by tenant. Used after the
     * family lock so the status observed under the lock is authoritative.
     */
    Optional<RefreshToken> findById(TenantId tenantId, UUID tokenId);

    /**
     * Locks one token family on its root row ({@code id = family_id}), serialising
     * every refresh, replay and logout of that family. The caller must already own a
     * surrounding database transaction, and must hold this lock for the whole
     * read-check-write span so a revocation can always see the complete family.
     *
     * @return the family root token, or empty when the family is corrupt
     */
    Optional<RefreshToken> findFamilyRootForUpdate(TenantId tenantId, UUID familyId);

    /**
     * Persists a newly issued token. Only the SHA-256 hash ever reaches the store;
     * the raw token value is generated and returned to the caller by the
     * application service exactly once and is never persisted.
     */
    void insert(RefreshToken token);

    /**
     * Persists a child token inside a transaction savepoint (nested transaction) so a
     * unique-constraint failure on {@code uq_refresh_tokens_one_child} rolls back only
     * the insert and leaves the caller's transaction usable for the family revocation.
     * Only the SHA-256 hash ever reaches the store.
     */
    void insertChild(RefreshToken token);

    /**
     * Marks a single ACTIVE token as CONSUMED, guarded by its current status so the
     * transition is compare-and-set against concurrent rotations.
     *
     * @return {@code true} only when exactly one ACTIVE row transitioned to CONSUMED
     */
    boolean consume(RefreshToken token, Instant consumedAt);

    /**
     * Revokes every still-ACTIVE token of one family. The token was already located
     * (and locked, for rotation) so only {@code tenant_id} and {@code family_id} are
     * needed; both are part of the explicit filter because no {@code TenantContext}
     * exists during rotation or logout.
     *
     * @return the number of tokens revoked
     */
    int revokeFamily(TenantId tenantId, UUID familyId, Instant revokedAt, String reason);
}
