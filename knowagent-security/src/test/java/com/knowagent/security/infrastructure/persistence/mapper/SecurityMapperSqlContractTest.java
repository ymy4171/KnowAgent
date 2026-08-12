package com.knowagent.security.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityMapperSqlContractTest {
    @Test
    void tenantLookupUsesRootTableRules() throws Exception {
        String sql = sql(TenantMapper.class.getMethod("selectActiveBySlug", String.class));

        assertThat(sql).contains("from tenants", "status = 'active'", "deleted_at is null");
        assertThat(sql).doesNotContain("tenant_id");
        assertThat(TenantMapper.class.getAnnotation(InterceptorIgnore.class).tenantLine()).isEqualTo("1");
    }

    @Test
    void preAuthenticationUserLookupCarriesExplicitTenant() throws Exception {
        Method method = UserMapper.class.getMethod("selectByTenantAndLoginName", java.util.UUID.class, String.class);
        String sql = sql(method);

        assertThat(sql).contains("tenant_id = #{tenantid}", "login_name = #{loginname}", "deleted_at is null");
        assertThat(method.getAnnotation(InterceptorIgnore.class).tenantLine()).isEqualTo("1");
    }

    @Test
    void effectiveRoleLookupScopesEveryTenantTableAndFiltersInactiveBindings() throws Exception {
        Method method = RoleMapper.class.getMethod("selectEffectiveByUser", java.util.UUID.class, java.util.UUID.class);
        String sql = sql(method);

        assertThat(sql).contains(
                "ur.tenant_id = #{tenantid}",
                "r.tenant_id = #{tenantid}",
                "u.tenant_id = #{tenantid}",
                "r.status = 'active'",
                "r.deleted_at is null",
                "ur.expires_at > current_timestamp");
        assertThat(method.getAnnotation(InterceptorIgnore.class).tenantLine()).isEqualTo("1");
    }

    @Test
    void refreshHashLookupIsTheDocumentedGlobalExceptionAndLockQueryLocks() throws Exception {
        Method normal = RefreshTokenMapper.class.getMethod("selectByTokenHash", String.class);
        Method locking = RefreshTokenMapper.class.getMethod("selectByTokenHashForUpdate", String.class);

        assertThat(sql(normal)).contains("where token_hash = #{tokenhash}").doesNotContain("tenant_id =");
        assertThat(sql(locking)).contains("where token_hash = #{tokenhash}", "for update").doesNotContain("tenant_id =");
        assertThat(normal.getAnnotation(InterceptorIgnore.class).tenantLine()).isEqualTo("1");
        assertThat(locking.getAnnotation(InterceptorIgnore.class).tenantLine()).isEqualTo("1");
    }

    @Test
    void bootstrapTenantLookupUsesRootTableRules() throws Exception {
        Method method = TenantMapper.class.getMethod("selectBySlug", String.class);
        String sql = sql(method);

        assertThat(sql).contains("from tenants", "slug = #{slug}", "deleted_at is null");
        assertThat(sql).doesNotContain("tenant_id");
        assertThat(method.getAnnotation(InterceptorIgnore.class).tenantLine()).isEqualTo("1");
    }

    @Test
    void bootstrapRoleLookupByCodeCarriesExplicitTenant() throws Exception {
        Method method = RoleMapper.class.getMethod("selectByTenantAndCode", java.util.UUID.class, String.class);
        String sql = sql(method);

        assertThat(sql).contains("tenant_id = #{tenantid}", "code = #{code}", "deleted_at is null");
        assertThat(method.getAnnotation(InterceptorIgnore.class).tenantLine()).isEqualTo("1");
    }

    @Test
    void bootstrapAssignmentExistenceIsScopedToTenantUserAndRole() throws Exception {
        Method method = UserRoleMapper.class.getMethod(
                "existsByTenantUserAndRole", java.util.UUID.class, java.util.UUID.class, java.util.UUID.class);
        String sql = sql(method);

        assertThat(sql).contains(
                "tenant_id = #{tenantid}", "user_id = #{userid}", "role_id = #{roleid}",
                "expires_at is null or expires_at > current_timestamp");
        assertThat(method.getAnnotation(InterceptorIgnore.class).tenantLine()).isEqualTo("1");
    }

    /**
     * The tenant-line plugin is bypassed only for the small, documented set of
     * pre-authentication lookups (login lookups plus the developer-admin bootstrap
     * existence checks). Any new @InterceptorIgnore(tenantLine = "1") method fails
     * this test on purpose: a bypassed method must be justified in a code review and
     * must keep an explicit tenant_id condition in its SQL (or be the tenant root
     * table, or the documented refresh-token hash exception).
     */
    @Test
    void onlyDocumentedPreAuthenticationMethodsBypassTenantLine() throws Exception {
        Set<String> bypassed = new TreeSet<>();
        for (Class<?> mapper : List.of(
                TenantMapper.class, UserMapper.class, RoleMapper.class,
                UserRoleMapper.class, RefreshTokenMapper.class)) {
            for (Method method : mapper.getDeclaredMethods()) {
                InterceptorIgnore ignore = method.getAnnotation(InterceptorIgnore.class);
                if (ignore != null && "1".equals(ignore.tenantLine())) {
                    bypassed.add(mapper.getSimpleName() + "." + method.getName());
                }
            }
        }
        assertThat(bypassed).containsExactly(
                "RefreshTokenMapper.selectByTokenHash",
                "RefreshTokenMapper.selectByTokenHashForUpdate",
                "RoleMapper.selectByTenantAndCode",
                "RoleMapper.selectEffectiveByUser",
                "TenantMapper.selectActiveBySlug",
                "TenantMapper.selectBySlug",
                "UserMapper.selectByTenantAndLoginName",
                "UserRoleMapper.existsByTenantUserAndRole");
    }

    private static String sql(Method method) {
        return String.join(" ", Arrays.stream(method.getAnnotation(Select.class).value())
                        .map(String::strip)
                        .toList())
                .replaceAll("\\s+", " ")
                .toLowerCase();
    }
}
