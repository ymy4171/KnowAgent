package com.knowagent.security.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

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

    private static String sql(Method method) {
        return String.join(" ", Arrays.stream(method.getAnnotation(Select.class).value())
                        .map(String::strip)
                        .toList())
                .replaceAll("\\s+", " ")
                .toLowerCase();
    }
}
