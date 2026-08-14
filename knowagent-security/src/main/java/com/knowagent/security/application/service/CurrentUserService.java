package com.knowagent.security.application.service;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.security.application.port.out.RoleRepository;
import com.knowagent.security.application.port.out.TenantRepository;
import com.knowagent.security.application.port.out.UserRepository;
import com.knowagent.security.domain.role.Role;
import com.knowagent.security.domain.tenant.Tenant;
import com.knowagent.security.domain.user.User;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Loads the current user's identity for the authenticated principal.
 *
 * <p>Scoped strictly to the tenant coming from the authenticated principal; the
 * tenant id is never accepted from the client. A missing tenant or user returns
 * {@link ErrorCode#RESOURCE_NOT_FOUND}, matching the rule that a resource a caller
 * cannot see (including one belonging to another tenant) is reported as 404.
 */
@Service
public class CurrentUserService {

    private final TenantRepository tenants;
    private final UserRepository users;
    private final RoleRepository roles;

    public CurrentUserService(TenantRepository tenants, UserRepository users, RoleRepository roles) {
        this.tenants = Objects.requireNonNull(tenants, "tenants must not be null");
        this.users = Objects.requireNonNull(users, "users must not be null");
        this.roles = Objects.requireNonNull(roles, "roles must not be null");
    }

    public CurrentUser currentUser(TenantId tenantId, UUID userId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");

        Tenant tenant = tenants.findActiveById(tenantId)
                .orElseThrow(CurrentUserService::notFound);
        User user = users.findById(tenantId, userId)
                .orElseThrow(CurrentUserService::notFound);

        List<Role> effectiveRoles = roles.findEffectiveByUser(tenantId, userId);
        Set<String> roleCodes = new LinkedHashSet<>();
        Set<String> permissions = new LinkedHashSet<>();
        for (Role role : effectiveRoles) {
            roleCodes.add(role.code());
            permissions.addAll(role.permissions());
        }

        return new CurrentUser(
                user.id(), tenantId, tenant.slug(), user.loginName(), user.displayName(),
                roleCodes, permissions);
    }

    private static BusinessException notFound() {
        return new BusinessException(
                ErrorCode.RESOURCE_NOT_FOUND, "The requested resource does not exist.");
    }
}
