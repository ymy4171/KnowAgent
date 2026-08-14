package com.knowagent.security.application.service;

import com.knowagent.common.tenant.TenantId;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The current user's identity as exposed by the {@code /users/me} endpoint.
 *
 * <p>Built from the database, never from client input: the tenant comes from the
 * authenticated principal and the user, tenant slug, roles and permissions are
 * loaded fresh so revoked roles or permissions take effect immediately. No
 * credential material (password hash, lock fields) is present.
 */
public record CurrentUser(
        UUID userId,
        TenantId tenantId,
        String tenantSlug,
        String loginName,
        String displayName,
        Set<String> roles,
        Set<String> permissions) {

    public CurrentUser {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(tenantSlug, "tenantSlug must not be null");
        Objects.requireNonNull(loginName, "loginName must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles must not be null"));
        permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions must not be null"));
    }
}
