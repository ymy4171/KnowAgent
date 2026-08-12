package com.knowagent.security.infrastructure.persistence.repository;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.security.application.port.out.AdminBootstrapRepository;
import com.knowagent.security.domain.role.Role;
import com.knowagent.security.domain.role.UserRole;
import com.knowagent.security.domain.tenant.Tenant;
import com.knowagent.security.domain.user.User;
import com.knowagent.security.infrastructure.persistence.converter.IdentityPersistenceConverter;
import com.knowagent.security.infrastructure.persistence.mapper.RoleMapper;
import com.knowagent.security.infrastructure.persistence.mapper.TenantMapper;
import com.knowagent.security.infrastructure.persistence.mapper.UserMapper;
import com.knowagent.security.infrastructure.persistence.mapper.UserRoleMapper;
import org.springframework.stereotype.Repository;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * MyBatis-Plus adapter for the bootstrap persistence boundary.
 *
 * <p>Reads reuse the pre-authentication mapper lookups (explicit {@code tenant_id},
 * bypassing the tenant-line plugin, which is empty before the first user exists).
 * Writes always set {@code tenantId} on the PO explicitly, so the tenant-line
 * interceptor trusts the PO value and never calls {@code TenantContext}, which also
 * has no value during bootstrap.
 */
@Repository
public class MyBatisAdminBootstrapRepository implements AdminBootstrapRepository {

    private final TenantMapper tenants;
    private final RoleMapper roles;
    private final UserMapper users;
    private final UserRoleMapper userRoles;

    public MyBatisAdminBootstrapRepository(
            TenantMapper tenants,
            RoleMapper roles,
            UserMapper users,
            UserRoleMapper userRoles) {
        this.tenants = Objects.requireNonNull(tenants, "tenants must not be null");
        this.roles = Objects.requireNonNull(roles, "roles must not be null");
        this.users = Objects.requireNonNull(users, "users must not be null");
        this.userRoles = Objects.requireNonNull(userRoles, "userRoles must not be null");
    }

    @Override
    public Optional<Tenant> findTenantBySlug(String slug) {
        Objects.requireNonNull(slug, "slug must not be null");
        return Optional.ofNullable(tenants.selectBySlug(slug))
                .map(IdentityPersistenceConverter::toDomain);
    }

    @Override
    public Optional<Role> findRoleByTenantAndCode(TenantId tenantId, String code) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(code, "code must not be null");
        return Optional.ofNullable(roles.selectByTenantAndCode(tenantId.value(), code))
                .map(IdentityPersistenceConverter::toDomain);
    }

    @Override
    public Optional<User> findUserByTenantAndLogin(TenantId tenantId, String loginName) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(loginName, "loginName must not be null");
        return Optional.ofNullable(users.selectByTenantAndLoginName(tenantId.value(), loginName))
                .map(IdentityPersistenceConverter::toDomain);
    }

    @Override
    public boolean existsUserRole(TenantId tenantId, UUID userId, UUID roleId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(roleId, "roleId must not be null");
        return userRoles.existsByTenantUserAndRole(tenantId.value(), userId, roleId) > 0;
    }

    @Override
    public void insertTenant(Tenant tenant) {
        Objects.requireNonNull(tenant, "tenant must not be null");
        int inserted = tenants.insert(IdentityPersistenceConverter.toPersistence(tenant));
        if (inserted != 1) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Unable to persist bootstrap tenant");
        }
    }

    @Override
    public void insertRole(Role role) {
        Objects.requireNonNull(role, "role must not be null");
        int inserted = roles.insert(IdentityPersistenceConverter.toPersistence(role));
        if (inserted != 1) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Unable to persist bootstrap role");
        }
    }

    @Override
    public void insertUser(User user) {
        Objects.requireNonNull(user, "user must not be null");
        int inserted = users.insert(IdentityPersistenceConverter.toPersistence(user));
        if (inserted != 1) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Unable to persist bootstrap user");
        }
    }

    @Override
    public void insertUserRole(UserRole userRole) {
        Objects.requireNonNull(userRole, "userRole must not be null");
        int inserted = userRoles.insert(IdentityPersistenceConverter.toPersistence(userRole));
        if (inserted != 1) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Unable to persist bootstrap user role assignment");
        }
    }
}
