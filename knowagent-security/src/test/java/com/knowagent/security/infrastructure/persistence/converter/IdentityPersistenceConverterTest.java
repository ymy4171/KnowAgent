package com.knowagent.security.infrastructure.persistence.converter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowagent.common.error.BusinessException;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.security.domain.role.Role;
import com.knowagent.security.domain.role.RoleStatus;
import com.knowagent.security.domain.tenant.Tenant;
import com.knowagent.security.domain.tenant.TenantStatus;
import com.knowagent.security.domain.token.RefreshTokenStatus;
import com.knowagent.security.domain.user.User;
import com.knowagent.security.domain.user.UserStatus;
import com.knowagent.security.infrastructure.persistence.entity.RefreshTokenPo;
import com.knowagent.security.infrastructure.persistence.entity.RolePo;
import com.knowagent.security.infrastructure.persistence.entity.TenantPo;
import com.knowagent.security.infrastructure.persistence.entity.UserPo;
import com.knowagent.security.infrastructure.persistence.entity.UserRolePo;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityPersistenceConverterTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final OffsetDateTime CREATED = OffsetDateTime.of(2026, 8, 11, 9, 30, 0, 0, ZoneOffset.ofHours(8));
    private static final OffsetDateTime UPDATED = CREATED.plusMinutes(10);

    @Test
    void convertsTenantWithUuidStatusVersionAndInstants() {
        TenantPo source = new TenantPo();
        var settings = OBJECT_MAPPER.createObjectNode().put("locale", "zh-CN");
        source.setId(UUID.randomUUID());
        source.setSlug("tenant-a");
        source.setName("Tenant A");
        source.setStatus(TenantStatus.ACTIVE);
        source.setSettings(settings);
        source.setVersion(3L);
        source.setCreatedAt(CREATED);
        source.setUpdatedAt(UPDATED);

        var tenant = IdentityPersistenceConverter.toDomain(source);

        assertThat(tenant.id().value()).isEqualTo(source.getId());
        assertThat(tenant.status()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(tenant.settings().get("locale").textValue()).isEqualTo("zh-CN");
        settings.put("locale", "changed");
        assertThat(tenant.settings().get("locale").textValue()).isEqualTo("zh-CN");
        assertThat(tenant.version()).isEqualTo(3L);
        assertThat(tenant.createdAt()).isEqualTo(CREATED.toInstant());
        assertThat(tenant.updatedAt()).isEqualTo(UPDATED.toInstant());
    }

    @Test
    void convertsUserWithoutExposingPasswordInToString() {
        UserPo source = userPo();

        var user = IdentityPersistenceConverter.toDomain(source);

        assertThat(user.id()).isEqualTo(source.getId());
        assertThat(user.tenantId().value()).isEqualTo(source.getTenantId());
        assertThat(user.status()).isEqualTo(UserStatus.LOCKED);
        assertThat(user.loginFailedCount()).isEqualTo(2);
        assertThat(user.lastFailedLoginAt()).isEqualTo(CREATED.plusMinutes(1).toInstant());
        assertThat(user.toString()).doesNotContain(source.getPasswordHash());
    }

    @Test
    void convertsRoleAndDefensivelyCopiesPermissions() {
        Set<String> permissions = new LinkedHashSet<>(Set.of("USER_READ", "USER_ADMIN"));
        RolePo source = new RolePo();
        source.setId(UUID.randomUUID());
        source.setTenantId(UUID.randomUUID());
        source.setCode("ADMIN");
        source.setName("Administrator");
        source.setPermissions(permissions);
        source.setIsSystem(true);
        source.setStatus(RoleStatus.ACTIVE);
        source.setVersion(5L);
        source.setCreatedAt(CREATED);
        source.setUpdatedAt(UPDATED);

        var role = IdentityPersistenceConverter.toDomain(source);
        permissions.clear();

        assertThat(role.permissions()).containsExactlyInAnyOrder("USER_READ", "USER_ADMIN");
        assertThatThrownBy(() -> role.permissions().add("MUTATE"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void convertsTenantUserAndRoleDomainBackToPersistence() {
        UUID tenantId = UUID.randomUUID();
        Tenant tenant = new Tenant(TenantId.of(tenantId), "acme", "Acme Co", TenantStatus.ACTIVE,
                OBJECT_MAPPER.createObjectNode().put("locale", "zh-CN"), 0L,
                CREATED.toInstant(), UPDATED.toInstant(), null);
        User user = new User(UUID.randomUUID(), tenant.id(), null, "admin@acme.test", "Acme Admin",
                "admin@acme.test", null, null, "$argon2id$test-hash", UserStatus.ACTIVE,
                0, null, null, null, 2L, CREATED.toInstant(), UPDATED.toInstant(), null);
        Role role = new Role(UUID.randomUUID(), tenant.id(), "ADMIN", "Administrator",
                "System administrator", Set.of("USER_READ", "ROLE_READ"), true,
                RoleStatus.ACTIVE, 1L, CREATED.toInstant(), UPDATED.toInstant(), null);

        TenantPo tenantPo = IdentityPersistenceConverter.toPersistence(tenant);
        UserPo userPo = IdentityPersistenceConverter.toPersistence(user);
        RolePo rolePo = IdentityPersistenceConverter.toPersistence(role);

        assertThat(tenantPo.getId()).isEqualTo(tenantId);
        assertThat(tenantPo.getSlug()).isEqualTo("acme");
        assertThat(tenantPo.getStatus()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(tenantPo.getCreatedAt().toInstant()).isEqualTo(CREATED.toInstant());
        assertThat(userPo.getTenantId()).isEqualTo(tenantId);
        assertThat(userPo.getPasswordHash()).isEqualTo("$argon2id$test-hash");
        assertThat(userPo.getVersion()).isEqualTo(2L);
        assertThat(rolePo.getCode()).isEqualTo("ADMIN");
        assertThat(rolePo.getPermissions()).containsExactlyInAnyOrder("USER_READ", "ROLE_READ");
        assertThat(rolePo.getIsSystem()).isTrue();
    }

    @Test
    void convertsUserRoleAndRefreshTokenOwnership() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UserRolePo assignment = new UserRolePo();
        assignment.setId(UUID.randomUUID());
        assignment.setTenantId(tenantId);
        assignment.setUserId(userId);
        assignment.setRoleId(UUID.randomUUID());
        assignment.setGrantedAt(CREATED);
        assignment.setExpiresAt(UPDATED);

        RefreshTokenPo token = new RefreshTokenPo();
        token.setId(UUID.randomUUID());
        token.setTenantId(tenantId);
        token.setUserId(userId);
        token.setFamilyId(token.getId());
        token.setTokenHash("a".repeat(64));
        token.setStatus(RefreshTokenStatus.ACTIVE);
        token.setIssuedAt(CREATED);
        token.setExpiresAt(CREATED.plusDays(1));
        token.setIssuedIp(InetAddress.getByName("203.0.113.10"));
        token.setUserAgent("persistence-test");
        token.setVersion(0L);

        var userRole = IdentityPersistenceConverter.toDomain(assignment);
        var assignmentPo = IdentityPersistenceConverter.toPersistence(userRole);
        var refreshToken = IdentityPersistenceConverter.toDomain(token);

        assertThat(userRole.isEffectiveAt(CREATED.plusMinutes(5).toInstant())).isTrue();
        assertThat(assignmentPo.getId()).isEqualTo(assignment.getId());
        assertThat(assignmentPo.getTenantId()).isEqualTo(tenantId);
        assertThat(assignmentPo.getGrantedAt().toInstant()).isEqualTo(userRole.grantedAt());
        assertThat(refreshToken.belongsTo(refreshToken.tenantId(), userId)).isTrue();
        assertThat(refreshToken.belongsTo(refreshToken.tenantId(), UUID.randomUUID())).isFalse();
        assertThat(refreshToken.issuedIp()).isEqualTo(token.getIssuedIp());
        assertThat(refreshToken.toString()).doesNotContain(token.getTokenHash());
    }

    @Test
    void convertsInvalidPersistenceRecordToStableBusinessException() {
        TenantPo source = new TenantPo();
        source.setId(UUID.randomUUID());
        source.setSlug("tenant-a");
        source.setName("Tenant A");
        source.setStatus(TenantStatus.ACTIVE);
        source.setSettings(OBJECT_MAPPER.createObjectNode());
        source.setCreatedAt(CREATED);
        source.setUpdatedAt(UPDATED);

        assertThatThrownBy(() -> IdentityPersistenceConverter.toDomain(source))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid tenant persistence record");
    }

    private static UserPo userPo() {
        UserPo source = new UserPo();
        source.setId(UUID.randomUUID());
        source.setTenantId(UUID.randomUUID());
        source.setLoginName("alice");
        source.setDisplayName("Alice");
        source.setPasswordHash("$test-only-hash$");
        source.setStatus(UserStatus.LOCKED);
        source.setLoginFailedCount(2);
        source.setLastFailedLoginAt(CREATED.plusMinutes(1));
        source.setLoginLockedUntil(CREATED.plusMinutes(30));
        source.setVersion(4L);
        source.setCreatedAt(CREATED);
        source.setUpdatedAt(UPDATED);
        return source;
    }
}
