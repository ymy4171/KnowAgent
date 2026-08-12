package com.knowagent.security.application.service;

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
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests for {@link AdminBootstrapService} idempotency and hashing. The
 * repository is an in-memory double with the same find-or-insert semantics as the
 * database adapter; transaction rollback is covered by the container integration
 * test ({@code knowagent-api} {@code AdminBootstrapIT}).
 */
class AdminBootstrapServiceTest {

    private static final String RAW_PASSWORD = "CorrectHorseBatteryStaple1";

    @Test
    void firstRunCreatesTenantAdminRoleUserAndBinding() {
        InMemoryAdminBootstrapRepository repository = new InMemoryAdminBootstrapRepository();
        AdminBootstrapService service = new AdminBootstrapService(repository, new PrefixPasswordHasher());

        service.initialize(new AdminBootstrapRequest("acme", null, "admin@acme.test", null, RAW_PASSWORD));

        assertThat(repository.tenants).hasSize(1);
        assertThat(repository.roles).hasSize(1);
        assertThat(repository.users).hasSize(1);
        assertThat(repository.bindings).hasSize(1);

        Tenant tenant = repository.tenants.getFirst();
        assertThat(tenant.slug()).isEqualTo("acme");
        assertThat(tenant.status()).isEqualTo(TenantStatus.ACTIVE);

        Role role = repository.roles.getFirst();
        assertThat(role.code()).isEqualTo(AdminBootstrapService.ADMIN_ROLE_CODE);
        assertThat(role.system()).isTrue();
        assertThat(role.status()).isEqualTo(RoleStatus.ACTIVE);
        assertThat(role.permissions()).isEqualTo(SecurityPermissions.ADMIN_ROLE_PERMISSIONS);

        User user = repository.users.getFirst();
        assertThat(user.loginName()).isEqualTo("admin@acme.test");
        assertThat(user.passwordHash()).startsWith("hashed:").doesNotContain(RAW_PASSWORD);
        assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);

        UserRole binding = repository.bindings.getFirst();
        assertThat(binding.userId()).isEqualTo(user.id());
        assertThat(binding.roleId()).isEqualTo(role.id());
        assertThat(binding.grantedBy()).isEqualTo(user.id());
    }

    @Test
    void repeatedRunsAreIdempotent() {
        InMemoryAdminBootstrapRepository repository = new InMemoryAdminBootstrapRepository();
        AdminBootstrapService service = new AdminBootstrapService(repository, new PrefixPasswordHasher());
        AdminBootstrapRequest request =
                new AdminBootstrapRequest("acme", null, "admin@acme.test", null, RAW_PASSWORD);

        service.initialize(request);
        service.initialize(request);
        service.initialize(request);

        assertThat(repository.tenants).hasSize(1);
        assertThat(repository.roles).hasSize(1);
        assertThat(repository.users).hasSize(1);
        assertThat(repository.bindings).hasSize(1);
    }

    @Test
    void reusesExistingTenantWhenOnlyAdminIsMissing() {
        InMemoryAdminBootstrapRepository repository = new InMemoryAdminBootstrapRepository();
        UUID existingTenantId = UUID.randomUUID();
        repository.tenants.add(new Tenant(TenantId.of(existingTenantId), "acme", "Acme Co",
                TenantStatus.ACTIVE, repository.emptySettings(), 0L,
                Instant.EPOCH, Instant.EPOCH, null));
        AdminBootstrapService service = new AdminBootstrapService(repository, new PrefixPasswordHasher());

        service.initialize(new AdminBootstrapRequest("acme", null, "admin@acme.test", null, RAW_PASSWORD));

        assertThat(repository.tenants).hasSize(1);
        assertThat(repository.roles).hasSize(1);
        assertThat(repository.users).hasSize(1);
        assertThat(repository.bindings).hasSize(1);
        assertThat(repository.roles.getFirst().tenantId().value()).isEqualTo(existingTenantId);
    }

    @Test
    void rejectsExistingSuspendedTenant() {
        InMemoryAdminBootstrapRepository repository = new InMemoryAdminBootstrapRepository();
        repository.tenants.add(new Tenant(TenantId.of(UUID.randomUUID()), "acme", "Acme Co",
                TenantStatus.SUSPENDED, repository.emptySettings(), 0L,
                Instant.EPOCH, Instant.EPOCH, null));
        AdminBootstrapService service = new AdminBootstrapService(repository, new PrefixPasswordHasher());

        assertThatThrownBy(() -> service.initialize(
                new AdminBootstrapRequest("acme", null, "admin@acme.test", null, RAW_PASSWORD)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUSPENDED")
                .hasMessageContaining("acme");
    }

    @Test
    void rejectsExistingDisabledTenant() {
        InMemoryAdminBootstrapRepository repository = new InMemoryAdminBootstrapRepository();
        repository.tenants.add(new Tenant(TenantId.of(UUID.randomUUID()), "acme", "Acme Co",
                TenantStatus.DISABLED, repository.emptySettings(), 0L,
                Instant.EPOCH, Instant.EPOCH, null));
        AdminBootstrapService service = new AdminBootstrapService(repository, new PrefixPasswordHasher());

        assertThatThrownBy(() -> service.initialize(
                new AdminBootstrapRequest("acme", null, "admin@acme.test", null, RAW_PASSWORD)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DISABLED");
    }

    @Test
    void validateExistingTenantAcceptsActiveTenant() {
        Tenant active = new Tenant(TenantId.of(UUID.randomUUID()), "acme", "Acme Co",
                TenantStatus.ACTIVE, new InMemoryAdminBootstrapRepository().emptySettings(), 0L,
                Instant.EPOCH, Instant.EPOCH, null);

        Tenant result = AdminBootstrapService.validateExistingTenant(active);

        assertThat(result).isSameAs(active);
    }

    @Test
    void expiredUserRoleBindingIsTreatedAsAbsentAndRecreated() {
        InMemoryAdminBootstrapRepository repository = new InMemoryAdminBootstrapRepository();
        AdminBootstrapService service = new AdminBootstrapService(repository, new PrefixPasswordHasher());

        // First run creates everything from scratch.
        service.initialize(new AdminBootstrapRequest("acme", null, "admin@acme.test", null, RAW_PASSWORD));
        assertThat(repository.bindings).hasSize(1);

        // Replace the first binding with one whose expires_at is firmly in the
        // past. grantedAt is set even earlier to satisfy the domain constraint
        // that expiresAt > grantedAt.
        UserRole expiredBinding = repository.bindings.getFirst();
        Instant past = Instant.now().minusSeconds(10);
        UserRole trulyExpired = new UserRole(
                expiredBinding.id(), expiredBinding.tenantId(),
                expiredBinding.userId(), expiredBinding.roleId(),
                expiredBinding.grantedBy(),
                past.minusSeconds(1), // grantedAt even earlier
                past);                // expires_at is 10 s ago → expired
        repository.bindings.clear();
        repository.bindings.add(trulyExpired);

        // Second run must see the binding as absent and insert a fresh one.
        service.initialize(new AdminBootstrapRequest("acme", null, "admin@acme.test", null, RAW_PASSWORD));
        assertThat(repository.bindings).hasSize(2);
        // The first binding is the old expired one, the second is the fresh null-expiry binding.
        assertThat(repository.bindings.get(0).expiresAt()).isNotNull();
        assertThat(repository.bindings.get(1).expiresAt()).isNull();
    }

    @Test
    void requestToStringRedactsPassword() {
        AdminBootstrapRequest request =
                new AdminBootstrapRequest("acme", null, "admin@acme.test", null, RAW_PASSWORD);

        String repr = request.toString();

        assertThat(repr).doesNotContain(RAW_PASSWORD)
                .contains("[REDACTED]")
                .contains("acme")
                .contains("admin@acme.test");
    }

    @Test
    void rejectsExistingIncompatibleAdminRole() {
        InMemoryAdminBootstrapRepository repository = new InMemoryAdminBootstrapRepository();
        UUID tenantId = UUID.randomUUID();
        repository.tenants.add(new Tenant(TenantId.of(tenantId), "acme", "Acme Co",
                TenantStatus.ACTIVE, repository.emptySettings(), 0L,
                Instant.EPOCH, Instant.EPOCH, null));
        // DISABLED role — should be rejected
        repository.roles.add(new Role(UUID.randomUUID(), TenantId.of(tenantId), "ADMIN",
                "Administrator", "Old admin", SecurityPermissions.ADMIN_ROLE_PERMISSIONS,
                true, RoleStatus.DISABLED, 0L, Instant.EPOCH, Instant.EPOCH, null));
        AdminBootstrapService service = new AdminBootstrapService(repository, new PrefixPasswordHasher());

        assertThatThrownBy(() -> service.initialize(
                new AdminBootstrapRequest("acme", null, "admin@acme.test", null, RAW_PASSWORD)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incompatible")
                .hasMessageContaining("DISABLED");
    }

    @Test
    void rejectsExistingNonSystemAdminRole() {
        InMemoryAdminBootstrapRepository repository = new InMemoryAdminBootstrapRepository();
        UUID tenantId = UUID.randomUUID();
        repository.tenants.add(new Tenant(TenantId.of(tenantId), "acme", "Acme Co",
                TenantStatus.ACTIVE, repository.emptySettings(), 0L,
                Instant.EPOCH, Instant.EPOCH, null));
        // Non-system role — should be rejected
        repository.roles.add(new Role(UUID.randomUUID(), TenantId.of(tenantId), "ADMIN",
                "Administrator", "Non-system admin", SecurityPermissions.ADMIN_ROLE_PERMISSIONS,
                false, RoleStatus.ACTIVE, 0L, Instant.EPOCH, Instant.EPOCH, null));
        AdminBootstrapService service = new AdminBootstrapService(repository, new PrefixPasswordHasher());

        assertThatThrownBy(() -> service.initialize(
                new AdminBootstrapRequest("acme", null, "admin@acme.test", null, RAW_PASSWORD)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incompatible")
                .hasMessageContaining("system=false");
    }

    @Test
    void rejectsExistingRoleWithInsufficientPermissions() {
        InMemoryAdminBootstrapRepository repository = new InMemoryAdminBootstrapRepository();
        UUID tenantId = UUID.randomUUID();
        repository.tenants.add(new Tenant(TenantId.of(tenantId), "acme", "Acme Co",
                TenantStatus.ACTIVE, repository.emptySettings(), 0L,
                Instant.EPOCH, Instant.EPOCH, null));
        // Missing most ADMIN permissions
        repository.roles.add(new Role(UUID.randomUUID(), TenantId.of(tenantId), "ADMIN",
                "Administrator", "Minimal admin", Set.of("USER_READ"),
                true, RoleStatus.ACTIVE, 0L, Instant.EPOCH, Instant.EPOCH, null));
        AdminBootstrapService service = new AdminBootstrapService(repository, new PrefixPasswordHasher());

        assertThatThrownBy(() -> service.initialize(
                new AdminBootstrapRequest("acme", null, "admin@acme.test", null, RAW_PASSWORD)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incompatible");
    }

    @Test
    void rejectsExistingNonActiveAdminUser() {
        InMemoryAdminBootstrapRepository repository = new InMemoryAdminBootstrapRepository();
        UUID tenantId = UUID.randomUUID();
        repository.tenants.add(new Tenant(TenantId.of(tenantId), "acme", "Acme Co",
                TenantStatus.ACTIVE, repository.emptySettings(), 0L,
                Instant.EPOCH, Instant.EPOCH, null));
        repository.roles.add(new Role(UUID.randomUUID(), TenantId.of(tenantId), "ADMIN",
                "Administrator", "desc", SecurityPermissions.ADMIN_ROLE_PERMISSIONS,
                true, RoleStatus.ACTIVE, 0L, Instant.EPOCH, Instant.EPOCH, null));
        // LOCKED user — should be rejected
        repository.users.add(new User(UUID.randomUUID(), TenantId.of(tenantId), null,
                "admin@acme.test", "Admin", null, null, null,
                "$hashed", UserStatus.LOCKED, 0, null, null, null,
                0L, Instant.EPOCH, Instant.EPOCH, null));
        AdminBootstrapService service = new AdminBootstrapService(repository, new PrefixPasswordHasher());

        assertThatThrownBy(() -> service.initialize(
                new AdminBootstrapRequest("acme", null, "admin@acme.test", null, RAW_PASSWORD)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LOCKED")
                .hasMessageContaining("admin@acme.test");
    }

    @Test
    void validateExistingAdminRoleAcceptsCompatibleRole() {
        UUID tenantId = UUID.randomUUID();
        Role compatible = new Role(UUID.randomUUID(), TenantId.of(tenantId), "ADMIN",
                "Administrator", "desc", SecurityPermissions.ADMIN_ROLE_PERMISSIONS,
                true, RoleStatus.ACTIVE, 0L, Instant.EPOCH, Instant.EPOCH, null);

        Role result = AdminBootstrapService.validateExistingAdminRole(compatible);

        assertThat(result).isSameAs(compatible);
    }

    @Test
    void validateExistingAdminUserAcceptsActiveUser() {
        UUID tenantId = UUID.randomUUID();
        User active = new User(UUID.randomUUID(), TenantId.of(tenantId), null,
                "admin@acme.test", "Admin", null, null, null,
                "$hashed", UserStatus.ACTIVE, 0, null, null, null,
                0L, Instant.EPOCH, Instant.EPOCH, null);

        User result = AdminBootstrapService.validateExistingAdminUser(active);

        assertThat(result).isSameAs(active);
    }

    /**
     * In-memory {@link AdminBootstrapRepository} double with the same find-or-insert
     * semantics as the database adapter: reads observe prior inserts within the same
     * logical run.
     */
    private static final class InMemoryAdminBootstrapRepository implements AdminBootstrapRepository {
        private final List<Tenant> tenants = new ArrayList<>();
        private final List<Role> roles = new ArrayList<>();
        private final List<User> users = new ArrayList<>();
        private final List<UserRole> bindings = new ArrayList<>();

        @Override
        public Optional<Tenant> findTenantBySlug(String slug) {
            return tenants.stream().filter(tenant -> tenant.slug().equals(slug)).findFirst();
        }

        @Override
        public Optional<Role> findRoleByTenantAndCode(TenantId tenantId, String code) {
            return roles.stream()
                    .filter(role -> role.tenantId().equals(tenantId) && role.code().equals(code))
                    .findFirst();
        }

        @Override
        public Optional<User> findUserByTenantAndLogin(TenantId tenantId, String loginName) {
            return users.stream()
                    .filter(user -> user.tenantId().equals(tenantId) && user.loginName().equals(loginName))
                    .findFirst();
        }

        @Override
        public boolean existsUserRole(TenantId tenantId, UUID userId, UUID roleId) {
            Instant now = Instant.now();
            return bindings.stream().anyMatch(binding -> binding.tenantId().equals(tenantId)
                    && binding.userId().equals(userId)
                    && binding.roleId().equals(roleId)
                    && (binding.expiresAt() == null || binding.expiresAt().isAfter(now)));
        }

        @Override
        public void insertTenant(Tenant tenant) {
            tenants.add(tenant);
        }

        @Override
        public void insertRole(Role role) {
            roles.add(role);
        }

        @Override
        public void insertUser(User user) {
            users.add(user);
        }

        @Override
        public void insertUserRole(UserRole userRole) {
            bindings.add(userRole);
        }

        private com.fasterxml.jackson.databind.JsonNode emptySettings() {
            return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        }
    }

    /**
     * Double that visibly transforms the raw value so tests can assert hashing
     * happened: the persisted string never contains the raw password (matching the
     * contract the real Argon2 encoder guarantees).
     */
    private static final class PrefixPasswordHasher implements PasswordHasher {
        @Override
        public String encode(CharSequence rawPassword) {
            return "hashed:" + Integer.toHexString(rawPassword.hashCode());
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            return encode(rawPassword).contentEquals(encodedPassword);
        }
    }
}
