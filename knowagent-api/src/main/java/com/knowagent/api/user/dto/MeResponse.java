package com.knowagent.api.user.dto;

import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * HTTP response body for {@code GET /api/v1/users/me}.
 *
 * <p>A pure HTTP DTO built by the controller from the authenticated principal and
 * the fresh database state loaded by {@code CurrentUserService}. Never exposes
 * credential material (password hashes, lock fields). Roles and permissions are
 * sorted for a deterministic response.
 */
public record MeResponse(
        UUID userId,
        UUID tenantId,
        String tenantSlug,
        String loginName,
        String displayName,
        Set<String> roles,
        Set<String> permissions) {

    public MeResponse {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(tenantSlug, "tenantSlug must not be null");
        Objects.requireNonNull(loginName, "loginName must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        roles = Set.copyOf(new TreeSet<>(roles));
        permissions = Set.copyOf(new TreeSet<>(permissions));
    }
}
