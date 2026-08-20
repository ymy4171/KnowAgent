package com.knowagent.model.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the tenant-isolation contract for the model-provider mapper: every custom
 * statement carries an explicit {@code tenant_id} and {@code deleted_at IS NULL}, and
 * no method bypasses the tenant-line plugin (which additionally injects the context
 * tenant as a fail-closed backstop).
 */
class ModelProviderMapperSqlContractTest {

    @Test
    void readQueriesCarryExplicitTenantAndFilterDeletedRows() throws Exception {
        for (String name : new String[]{"selectByIdAndTenant", "selectByIdAndTenantForUpdate",
                "selectByIdAndTenantForKeyShare", "selectActiveByKey", "selectPage"}) {
            Method method = method(ModelProviderMapper.class, name);
            String sql = sql(method);
            assertThat(sql).as(name).contains("tenant_id = #{tenantid}", "deleted_at is null");
            assertThat(method.getAnnotation(InterceptorIgnore.class)).as(name).isNull();
        }
    }

    @Test
    void deletionLockQueryUsesForUpdate() throws Exception {
        Method method = method(ModelProviderMapper.class, "selectByIdAndTenantForUpdate");
        assertThat(sql(method)).contains("tenant_id = #{tenantid}", "deleted_at is null", "for update");
    }

    @Test
    void providerUsageLockQueryUsesForKeyShare() throws Exception {
        // The knowledge-base create/update reads its bound providers with FOR KEY SHARE:
        // it must re-evaluate deleted_at IS NULL after the lock is granted (so a provider
        // deleted while waiting surfaces as not-found) and must not bypass the tenant line.
        Method method = method(ModelProviderMapper.class, "selectByIdAndTenantForKeyShare");
        assertThat(sql(method)).contains("tenant_id = #{tenantid}", "deleted_at is null", "for key share");
        assertThat(method.getAnnotation(InterceptorIgnore.class)).isNull();
    }

    @Test
    void countCarriesExplicitTenantAndFiltersDeletedRows() throws Exception {
        Method method = method(ModelProviderMapper.class, "countAll");
        String sql = sql(method);
        assertThat(sql).contains("tenant_id = #{tenantid}", "deleted_at is null");
        assertThat(method.getAnnotation(InterceptorIgnore.class)).isNull();
    }

    @Test
    void updateAndSoftDeleteAreTenantScopedAndVersionGuarded() throws Exception {
        Method update = method(ModelProviderMapper.class, "updateConfig");
        String updateSql = sql(update);
        assertThat(updateSql).contains("tenant_id = #{tenantid}", "deleted_at is null", "version = #{version}");
        assertThat(update.getAnnotation(InterceptorIgnore.class)).isNull();

        Method delete = method(ModelProviderMapper.class, "softDelete");
        String deleteSql = sql(delete);
        assertThat(deleteSql).contains("tenant_id = #{tenantid}", "deleted_at is null", "version = #{version}");
        assertThat(delete.getAnnotation(InterceptorIgnore.class)).isNull();
    }

    private static Method method(Class<?> mapper, String name) throws NoSuchMethodException {
        for (Method method : mapper.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        throw new NoSuchMethodException(mapper.getSimpleName() + "." + name);
    }

    private static String sql(Method method) {
        String[] value = method.isAnnotationPresent(Select.class)
                ? method.getAnnotation(Select.class).value()
                : method.getAnnotation(Update.class).value();
        return String.join(" ", Arrays.stream(value).map(String::strip).toList())
                .replaceAll("\\s+", " ")
                .toLowerCase();
    }
}
