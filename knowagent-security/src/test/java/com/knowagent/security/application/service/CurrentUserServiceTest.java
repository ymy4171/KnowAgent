package com.knowagent.security.application.service;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.security.application.port.out.RoleRepository;
import com.knowagent.security.application.port.out.TenantRepository;
import com.knowagent.security.application.port.out.UserRepository;
import com.knowagent.security.domain.role.Role;
import com.knowagent.security.domain.role.RoleStatus;
import com.knowagent.security.domain.tenant.Tenant;
import com.knowagent.security.domain.tenant.TenantStatus;
import com.knowagent.security.domain.user.User;
import com.knowagent.security.domain.user.UserPage;
import com.knowagent.security.domain.user.UserStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrentUserServiceTest {

    @Test
    void currentUserReturnsIdentityRolesAndPermissions() {
        Fakes fakes = Fakes.withTenantUserAndAdmin();
        CurrentUserService service = fakes.service();

        CurrentUser current = service.currentUser(fakes.tenant().id(), fakes.user().id());

        assertThat(current.userId()).isEqualTo(fakes.user().id());
        assertThat(current.tenantId()).isEqualTo(fakes.tenant().id());
        assertThat(current.tenantSlug()).isEqualTo("acme");
        assertThat(current.loginName()).isEqualTo("admin@acme.test");
        assertThat(current.displayName()).isEqualTo("Admin User");
        assertThat(current.roles()).containsExactly("ADMIN");
        assertThat(current.permissions()).contains("USER_READ", "TENANT_WRITE");
    }

    @Test
    void crossTenantUserIdIsNotFound() {
        Fakes fakes = Fakes.withTwoTenants();
        CurrentUserService service = fakes.service();

        // The user id belongs to tenant B; querying under tenant A must be a 404-style error.
        assertThatThrownBy(() -> service.currentUser(fakes.tenantA().id(), fakes.userB().id()))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void missingTenantIsNotFound() {
        Fakes fakes = Fakes.withTenantUserAndAdmin();
        CurrentUserService service = fakes.service();

        assertThatThrownBy(() -> service.currentUser(TenantId.of(UUID.randomUUID()), fakes.user().id()))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void suspendedTenantIsNotFound() {
        Fakes fakes = Fakes.withTenantUserAndAdmin();
        fakes.suspendTenant();
        CurrentUserService service = fakes.service();

        assertThatThrownBy(() -> service.currentUser(fakes.tenant().id(), fakes.user().id()))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void missingUserIsNotFound() {
        Fakes fakes = Fakes.withTenantUserAndAdmin();
        CurrentUserService service = fakes.service();

        assertThatThrownBy(() -> service.currentUser(fakes.tenant().id(), UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    private static final class Fakes implements TenantRepository, UserRepository, RoleRepository {

        private final List<Tenant> tenants = new ArrayList<>();
        private final List<User> users = new ArrayList<>();
        private final Map<String, List<Role>> rolesByTenantUser = new HashMap<>();

        static Fakes withTenantUserAndAdmin() {
            Fakes fakes = new Fakes();
            fakes.addTenantUserAndAdmin();
            return fakes;
        }

        static Fakes withTwoTenants() {
            Fakes fakes = new Fakes();
            fakes.addTenantUserAndAdmin();
            fakes.addTenantUserAndAdmin();
            return fakes;
        }

        private void addTenantUserAndAdmin() {
            Instant now = Instant.now();
            String slug = tenants.isEmpty() ? "acme" : "beta";
            Tenant tenant = new Tenant(TenantId.of(UUID.randomUUID()), slug, slug, TenantStatus.ACTIVE,
                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(),
                    0L, now, now, null);
            tenants.add(tenant);
            User user = new User(UUID.randomUUID(), tenant.id(), null, "admin@" + slug + ".test",
                    "Admin User", null, null, null, "hashed:value", UserStatus.ACTIVE,
                    0, null, null, null, 0L, now, now, null);
            users.add(user);
            Role role = new Role(UUID.randomUUID(), tenant.id(), "ADMIN", "Admin", null,
                    Set.of("USER_READ", "TENANT_WRITE"), true, RoleStatus.ACTIVE, 0L, now, now, null);
            rolesByTenantUser.computeIfAbsent(key(tenant.id(), user.id()), k -> new ArrayList<>()).add(role);
        }

        CurrentUserService service() {
            return new CurrentUserService(this, this, this);
        }

        Tenant tenant() {
            return tenants.getFirst();
        }

        Tenant tenantA() {
            return tenants.getFirst();
        }

        User user() {
            return users.getFirst();
        }

        User userB() {
            return users.get(1);
        }

        void suspendTenant() {
            Tenant tenant = tenant();
            tenants.set(0, new Tenant(tenant.id(), tenant.slug(), tenant.name(), TenantStatus.SUSPENDED,
                    tenant.settings(), tenant.version(), tenant.createdAt(), tenant.updatedAt(), tenant.deletedAt()));
        }

        // TenantRepository
        @Override
        public Optional<Tenant> findActiveBySlug(String slug) {
            return tenants.stream()
                    .filter(t -> t.slug().equals(slug) && t.status() == TenantStatus.ACTIVE && t.deletedAt() == null)
                    .findFirst();
        }

        @Override
        public Optional<Tenant> findActiveById(TenantId tenantId) {
            return tenants.stream()
                    .filter(t -> t.id().equals(tenantId) && t.status() == TenantStatus.ACTIVE && t.deletedAt() == null)
                    .findFirst();
        }

        // UserRepository
        @Override
        public Optional<User> findByTenantAndLoginName(TenantId tenantId, String loginName) {
            return users.stream()
                    .filter(u -> u.tenantId().equals(tenantId) && u.loginName().equals(loginName) && u.deletedAt() == null)
                    .findFirst();
        }

        @Override
        public Optional<User> findById(TenantId tenantId, UUID userId) {
            return users.stream()
                    .filter(u -> u.tenantId().equals(tenantId) && u.id().equals(userId) && u.deletedAt() == null)
                    .findFirst();
        }

        @Override
        public boolean updateLoginState(User user) {
            return false;
        }

        @Override
        public int recordLoginFailure(TenantId tenantId, UUID userId, Instant now,
                                      int maxFailedAttempts, Instant lockUntil) {
            // Not exercised by the current-user service; present only to satisfy
            // the port contract.
            return 0;
        }

        @Override
        public UserPage search(TenantId tenantId, String keywordPattern, UserStatus status,
                               int page, int size) {
            // Not exercised by the current-user service; present only to satisfy
            // the port contract.
            throw new UnsupportedOperationException("search is not used by CurrentUserService");
        }

        // RoleRepository
        @Override
        public List<Role> findEffectiveByUser(TenantId tenantId, UUID userId) {
            return List.copyOf(rolesByTenantUser.getOrDefault(key(tenantId, userId), List.of()));
        }

        private static String key(TenantId tenantId, UUID userId) {
            return tenantId.value() + ":" + userId;
        }
    }
}
