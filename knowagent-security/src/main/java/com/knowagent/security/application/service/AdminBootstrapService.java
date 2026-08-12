package com.knowagent.security.application.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.security.application.port.out.AdminBootstrapRepository;
import com.knowagent.security.application.port.out.PasswordHasher;
import com.knowagent.security.domain.role.Role;
import com.knowagent.security.domain.role.RoleStatus;
import com.knowagent.security.domain.role.SecurityPermissions;
import com.knowagent.security.domain.role.UserRole;
import com.knowagent.security.domain.tenant.Tenant;
import com.knowagent.security.domain.tenant.TenantStatus;
import com.knowagent.security.domain.user.User;
import com.knowagent.security.domain.user.UserStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Idempotent, transactional bootstrap of the initial tenant, the ADMIN system role
 * and the admin user.
 *
 * <p>Runs entirely inside one transaction: {@link #initialize} is
 * {@link Transactional} and any step failure rolls back every insert made in that
 * call. Each of the four rows is created only when it does not already exist
 * (looked up by a natural key: slug / tenant+code / tenant+login / tenant+user+role),
 * so repeated startups never produce duplicates.
 *
 * <p>When an existing tenant, role or user is found, its state is validated against
 * the bootstrap expectations: the tenant must be {@code ACTIVE} (a SUSPENDED or
 * DISABLED tenant would prevent login); the ADMIN role must be {@code ACTIVE}, marked
 * system, and carry every permission in
 * {@link SecurityPermissions#ADMIN_ROLE_PERMISSIONS}; the admin user must be
 * {@code ACTIVE}. Any mismatch causes startup to fail fast rather than silently
 * producing an unusable administrator account.
 *
 * <p>The admin password is encoded with the injected {@link PasswordHasher}
 * (Argon2id) before it reaches the repository, and the raw value is never logged or
 * stored. The created UUIDs are pre-generated in Java; the database never assigns
 * an identity this service needs to guess afterwards.
 */
@Service
public class AdminBootstrapService implements AdminBootstrap {

    /** Stable, uppercase role code matching the {@code roles.code} CHECK constraint. */
    public static final String ADMIN_ROLE_CODE = "ADMIN";

    private static final String ADMIN_ROLE_NAME = "Administrator";
    private static final String ADMIN_ROLE_DESCRIPTION =
            "System administrator role created during developer bootstrap.";

    private final AdminBootstrapRepository repository;
    private final PasswordHasher passwordHasher;

    public AdminBootstrapService(AdminBootstrapRepository repository, PasswordHasher passwordHasher) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.passwordHasher = Objects.requireNonNull(passwordHasher, "passwordHasher must not be null");
    }

    @Override
    @Transactional
    public void initialize(AdminBootstrapRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        Instant now = Instant.now();

        Tenant tenant = repository.findTenantBySlug(request.tenantSlug())
                .map(AdminBootstrapService::validateExistingTenant)
                .orElseGet(() -> {
                    Tenant created = new Tenant(
                            TenantId.of(UUID.randomUUID()),
                            request.tenantSlug(),
                            request.tenantName(),
                            TenantStatus.ACTIVE,
                            emptySettings(),
                            0L,
                            now,
                            now,
                            null);
                    repository.insertTenant(created);
                    return created;
                });

        Role adminRole = repository.findRoleByTenantAndCode(tenant.id(), ADMIN_ROLE_CODE)
                .map(AdminBootstrapService::validateExistingAdminRole)
                .orElseGet(() -> {
                    Role created = new Role(
                            UUID.randomUUID(),
                            tenant.id(),
                            ADMIN_ROLE_CODE,
                            ADMIN_ROLE_NAME,
                            ADMIN_ROLE_DESCRIPTION,
                            SecurityPermissions.ADMIN_ROLE_PERMISSIONS,
                            true,
                            RoleStatus.ACTIVE,
                            0L,
                            now,
                            now,
                            null);
                    repository.insertRole(created);
                    return created;
                });

        User adminUser = repository.findUserByTenantAndLogin(tenant.id(), request.adminLogin())
                .map(AdminBootstrapService::validateExistingAdminUser)
                .orElseGet(() -> {
                    User created = new User(
                            UUID.randomUUID(),
                            tenant.id(),
                            null,
                            request.adminLogin(),
                            request.adminDisplayName(),
                            null,
                            null,
                            null,
                            passwordHasher.encode(request.adminPassword()),
                            UserStatus.ACTIVE,
                            0,
                            null,
                            null,
                            null,
                            0L,
                            now,
                            now,
                            null);
                    repository.insertUser(created);
                    return created;
                });

        if (!repository.existsUserRole(tenant.id(), adminUser.id(), adminRole.id())) {
            repository.insertUserRole(new UserRole(
                    UUID.randomUUID(),
                    tenant.id(),
                    adminUser.id(),
                    adminRole.id(),
                    adminUser.id(),
                    now,
                    null));
        }
    }

    private static ObjectNode emptySettings() {
        return JsonNodeFactory.instance.objectNode();
    }

    /**
     * Validates that an already-persisted tenant is active. A SUSPENDED or DISABLED
     * tenant would let the bootstrap complete successfully, but login queries
     * ({@code TenantMapper#selectActiveBySlug}) only return ACTIVE tenants, so the
     * admin user could never authenticate.
     */
    static Tenant validateExistingTenant(Tenant tenant) {
        if (tenant.status() != TenantStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Tenant '" + tenant.slug() + "' already exists but has status " + tenant.status()
                            + "; the bootstrap requires an ACTIVE tenant."
                            + " Reactivate the tenant before re-running the bootstrap.");
        }
        return tenant;
    }

    /**
     * Validates that an already-persisted ADMIN role is compatible with the
     * bootstrap contract: active, system-scoped, and holding at least the
     * {@link SecurityPermissions#ADMIN_ROLE_PERMISSIONS} set. A mismatch means an
     * earlier bootstrap or migration left the role in an unexpected state; fail
     * fast rather than silently reusing a role that cannot grant admin access.
     */
    static Role validateExistingAdminRole(Role role) {
        boolean compatible = role.status() == RoleStatus.ACTIVE
                && role.system()
                && role.permissions().containsAll(SecurityPermissions.ADMIN_ROLE_PERMISSIONS);
        if (!compatible) {
            throw new IllegalStateException(
                    "An ADMIN role already exists for this tenant but is incompatible with the bootstrap contract"
                            + " (status=" + role.status()
                            + ", system=" + role.system()
                            + ", permissions=" + role.permissions() + ")."
                            + " Fix the existing role before re-running the bootstrap.");
        }
        return role;
    }

    /**
     * Validates that an already-persisted admin user is active. A disabled or
     * locked user cannot authenticate, so reusing it would produce a "successful"
     * bootstrap with an unusable administrator account.
     */
    static User validateExistingAdminUser(User user) {
        if (user.status() != UserStatus.ACTIVE) {
            throw new IllegalStateException(
                    "User '" + user.loginName() + "' already exists for this tenant but has status "
                            + user.status() + "; the bootstrap requires an ACTIVE admin user."
                            + " Fix the existing user before re-running the bootstrap.");
        }
        return user;
    }
}
