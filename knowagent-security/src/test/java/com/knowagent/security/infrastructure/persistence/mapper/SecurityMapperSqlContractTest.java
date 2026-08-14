package com.knowagent.security.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.knowagent.security.infrastructure.persistence.entity.UserPo;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
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
    void userLookupByIdCarriesExplicitTenant() throws Exception {
        Method method = UserMapper.class.getMethod("selectByIdAndTenant", java.util.UUID.class, java.util.UUID.class);
        String sql = sql(method);

        assertThat(sql).contains("tenant_id = #{tenantid}", "id = #{userid}", "deleted_at is null");
        assertThat(method.getAnnotation(InterceptorIgnore.class).tenantLine()).isEqualTo("1");
    }

    @Test
    void loginStateUpdateIsTenantScopedAndVersionGuarded() throws Exception {
        Method method = UserMapper.class.getMethod("updateLoginState", UserPo.class);
        String updateSql = updateSql(method);

        assertThat(updateSql).contains(
                "update users",
                "login_failed_count = #{loginfailedcount}",
                "last_failed_login_at = #{lastfailedloginat}",
                "login_locked_until = #{loginlockeduntil}",
                "last_login_at = #{lastloginat}",
                "status = #{status}",
                "where tenant_id = #{tenantid}",
                "id = #{id}",
                "version = #{version}");
        assertThat(method.getAnnotation(InterceptorIgnore.class).tenantLine()).isEqualTo("1");
    }

    @Test
    void failureIncrementIsTenantScopedAndIncrementsInTheDatabase() throws Exception {
        Method method = UserMapper.class.getMethod(
                "recordLoginFailure",
                java.util.UUID.class, java.util.UUID.class, java.time.Instant.class,
                int.class, java.time.Instant.class);
        String updateSql = updateSql(method);

        assertThat(updateSql).contains(
                "update users",
                // The increment happens in the database itself, so concurrent failed
                // attempts never lose a count.
                "login_failed_count = login_failed_count + 1",
                "where tenant_id = #{tenantid}",
                "id = #{userid}",
                "deleted_at is null");
        assertThat(updateSql).doesNotContain("version = #{version}");
        assertThat(method.getAnnotation(InterceptorIgnore.class).tenantLine()).isEqualTo("1");
    }

    @Test
    void tenantLookupByIdUsesRootTableRules() throws Exception {
        Method method = TenantMapper.class.getMethod("selectActiveById", java.util.UUID.class);
        String sql = sql(method);

        assertThat(sql).contains("from tenants", "id = #{id}", "status = 'active'", "deleted_at is null");
        assertThat(sql).doesNotContain("tenant_id");
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
    void refreshHashLookupIsTheDocumentedGlobalException() throws Exception {
        Method method = RefreshTokenMapper.class.getMethod("selectByTokenHash", String.class);

        assertThat(sql(method)).contains("where token_hash = #{tokenhash}").doesNotContain("tenant_id =");
        assertThat(method.getAnnotation(InterceptorIgnore.class).tenantLine()).isEqualTo("1");
    }

    @Test
    void refreshTokenReadByIdIsTenantScoped() throws Exception {
        Method method = RefreshTokenMapper.class.getMethod(
                "selectByIdAndTenant", java.util.UUID.class, java.util.UUID.class);
        String sql = sql(method);

        assertThat(sql).contains("from refresh_tokens", "tenant_id = #{tenantid}", "id = #{tokenid}");
        assertThat(method.getAnnotation(InterceptorIgnore.class).tenantLine()).isEqualTo("1");
    }

    @Test
    void familyRootLockIsTenantScopedAndLocksByRootId() throws Exception {
        Method method = RefreshTokenMapper.class.getMethod(
                "selectFamilyRootForUpdate", java.util.UUID.class, java.util.UUID.class);
        String sql = sql(method);

        // The whole family is serialised on the root row (id = family_id): the lock
        // is keyed by the family id and scoped by tenant, so a replay revocation and
        // a successor rotation can never run against each other.
        assertThat(sql).contains(
                "from refresh_tokens",
                "tenant_id = #{tenantid}",
                "id = #{familyid}",
                "for update");
        assertThat(method.getAnnotation(InterceptorIgnore.class).tenantLine()).isEqualTo("1");
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
    void bootstrapAssignmentUpsertIsTenantScopedAndReactivatesOnlyExpiredRows() throws Exception {
        Method method = UserRoleMapper.class.getMethod(
                "ensureEffectiveAssignment",
                java.util.UUID.class, java.util.UUID.class, java.util.UUID.class,
                java.util.UUID.class, java.util.UUID.class, java.time.OffsetDateTime.class);
        String sql = insertSql(method);

        assertThat(sql).contains(
                "insert into user_roles",
                "#{tenantid}", "#{userid}", "#{roleid}",
                "on conflict on constraint uq_user_roles_assignment",
                "do update set",
                "expires_at = null",
                "user_roles.expires_at <= current_timestamp");
        assertThat(method.getAnnotation(InterceptorIgnore.class).tenantLine()).isEqualTo("1");
    }

    @Test
    void consumptionIsStatusGuardedAndTenantScoped() throws Exception {
        Method method = RefreshTokenMapper.class.getMethod(
                "consumeActive",
                java.util.UUID.class, java.util.UUID.class, java.time.Instant.class);
        String updateSql = updateSql(method);

        assertThat(updateSql).contains(
                "update refresh_tokens",
                "status = 'consumed'",
                "consumed_at = #{consumedat}",
                "version = version + 1",
                "where tenant_id = #{tenantid}",
                "id = #{tokenid}",
                "status = 'active'");
        assertThat(method.getAnnotation(InterceptorIgnore.class).tenantLine()).isEqualTo("1");
    }

    @Test
    void familyRevocationIsTenantScopedAndRecordsTheStableReason() throws Exception {
        Method method = RefreshTokenMapper.class.getMethod(
                "revokeActiveFamily",
                java.util.UUID.class, java.util.UUID.class,
                java.time.Instant.class, String.class);
        String updateSql = updateSql(method);

        assertThat(updateSql).contains(
                "update refresh_tokens",
                "status = 'revoked'",
                "revoked_at = #{revokedat}",
                "revoke_reason = #{reason}",
                "version = version + 1",
                "where tenant_id = #{tenantid}",
                "family_id = #{familyid}",
                "status = 'active'");
        assertThat(method.getAnnotation(InterceptorIgnore.class).tenantLine()).isEqualTo("1");
    }

    @Test
    void userPageAndCountQueriesCarryExplicitTenantAndStayUnderTenantLine() throws Exception {
        Method page = UserMapper.class.getMethod(
                "selectUserPage", java.util.UUID.class, String.class, String.class, int.class, int.class);
        String pageSql = sql(page);

        // The authenticated user-management queries are explicitly tenant-scoped
        // (never rely on the plugin alone) but deliberately NOT in the bypass
        // whitelist: they run inside an authenticated request where the plugin
        // additionally injects the context tenant as a fail-closed backstop.
        assertThat(pageSql).contains(
                "tenant_id = #{tenantid}",
                "deleted_at is null",
                "limit #{limit}",
                "offset #{offset}");
        assertThat(page.getAnnotation(InterceptorIgnore.class)).isNull();

        Method count = UserMapper.class.getMethod(
                "countUsers", java.util.UUID.class, String.class, String.class);
        String countSql = sql(count);

        // The count must scope by the same explicit tenant condition as the page.
        assertThat(countSql).contains("tenant_id = #{tenantid}", "deleted_at is null");
        assertThat(count.getAnnotation(InterceptorIgnore.class)).isNull();
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
                "RefreshTokenMapper.consumeActive",
                "RefreshTokenMapper.revokeActiveFamily",
                "RefreshTokenMapper.selectByIdAndTenant",
                "RefreshTokenMapper.selectByTokenHash",
                "RefreshTokenMapper.selectFamilyRootForUpdate",
                "RoleMapper.selectByTenantAndCode",
                "RoleMapper.selectEffectiveByUser",
                "TenantMapper.selectActiveById",
                "TenantMapper.selectActiveBySlug",
                "TenantMapper.selectBySlug",
                "UserMapper.recordLoginFailure",
                "UserMapper.selectByIdAndTenant",
                "UserMapper.selectByTenantAndLoginName",
                "UserMapper.updateLoginState",
                "UserRoleMapper.ensureEffectiveAssignment");
    }

    private static String sql(Method method) {
        return String.join(" ", Arrays.stream(method.getAnnotation(Select.class).value())
                        .map(String::strip)
                        .toList())
                .replaceAll("\\s+", " ")
                .toLowerCase();
    }

    private static String insertSql(Method method) {
        return String.join(" ", Arrays.stream(method.getAnnotation(Insert.class).value())
                        .map(String::strip)
                        .toList())
                .replaceAll("\\s+", " ")
                .toLowerCase();
    }

    private static String updateSql(Method method) {
        return String.join(" ", Arrays.stream(method.getAnnotation(Update.class).value())
                        .map(String::strip)
                        .toList())
                .replaceAll("\\s+", " ")
                .toLowerCase();
    }
}
