package com.knowagent.security.application.port.out;

import com.knowagent.common.tenant.TenantId;
import com.knowagent.security.domain.role.Role;
import com.knowagent.security.domain.role.UserRole;
import com.knowagent.security.domain.tenant.Tenant;
import com.knowagent.security.domain.user.User;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence boundary for the developer-admin bootstrap flow.
 *
 * <p>Unlike the login-oriented read repositories, this boundary must locate rows that
 * may already exist regardless of status (so a repeated startup reuses them instead
 * of inserting duplicates) and must insert new rows. Every read carries an explicit
 * {@code tenant_id} and runs before any authentication exists, so it participates in
 * the documented pre-authentication tenant-line exception.
 */
public interface AdminBootstrapRepository {

    /** Finds a non-deleted tenant by slug, regardless of status. */
    Optional<Tenant> findTenantBySlug(String slug);

    /** Finds a non-deleted role by code inside one tenant, regardless of status. */
    Optional<Role> findRoleByTenantAndCode(TenantId tenantId, String code);

    /** Finds a non-deleted user by login name inside one tenant, regardless of status. */
    Optional<User> findUserByTenantAndLogin(TenantId tenantId, String loginName);

    /** Returns {@code true} when the user-to-role assignment already exists. */
    boolean existsUserRole(TenantId tenantId, UUID userId, UUID roleId);

    void insertTenant(Tenant tenant);

    void insertRole(Role role);

    void insertUser(User user);

    void insertUserRole(UserRole userRole);
}
