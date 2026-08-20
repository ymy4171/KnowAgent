package com.knowagent.knowledge.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the tenant-isolation contract for the knowledge-base mapper: every custom
 * statement carries an explicit {@code tenant_id} and {@code deleted_at IS NULL}, and
 * no method bypasses the tenant-line plugin (which additionally injects the context
 * tenant as a fail-closed backstop). The file-reference check is tenant- and
 * knowledge-base-scoped.
 */
class KnowledgeBaseMapperSqlContractTest {

    @Test
    void readQueriesCarryExplicitTenantAndFilterDeletedRows() throws Exception {
        for (String name : new String[]{"selectByIdAndTenant", "selectByIdAndTenantForUpdate",
                "selectByIdAndTenantForKeyShare", "selectActiveBySlug", "selectPage"}) {
            Method method = method(KnowledgeBaseMapper.class, name);
            String sql = sql(method);
            assertThat(sql).as(name).contains("tenant_id = #{tenantid}", "deleted_at is null");
            assertThat(method.getAnnotation(InterceptorIgnore.class)).as(name).isNull();
        }
    }

    @Test
    void deletionLockQueryUsesForUpdate() throws Exception {
        Method method = method(KnowledgeBaseMapper.class, "selectByIdAndTenantForUpdate");
        assertThat(sql(method)).contains("tenant_id = #{tenantid}", "deleted_at is null", "for update");
    }

    @Test
    void fileCreationLockQueryUsesForKeyShare() throws Exception {
        Method method = method(KnowledgeBaseMapper.class, "selectByIdAndTenantForKeyShare");
        assertThat(sql(method)).contains("tenant_id = #{tenantid}", "deleted_at is null", "for key share");
    }

    @Test
    void pageAndCountShareTheSameFilterAndPageIsOrdered() throws Exception {
        Method page = method(KnowledgeBaseMapper.class, "selectPage");
        String pageSql = sql(page);
        assertThat(pageSql).contains("limit #{limit}", "offset #{offset}", "order by created_at desc");
        assertThat(pageSql).contains("lower(name) like lower(#{namepattern})");
        assertThat(pageSql).contains("lower(slug) like lower(#{slugpattern})");
        assertThat(pageSql).contains("status = #{status}");

        Method count = method(KnowledgeBaseMapper.class, "countAll");
        String countSql = sql(count);
        assertThat(countSql).contains("tenant_id = #{tenantid}", "deleted_at is null");
        assertThat(countSql).contains("lower(name) like lower(#{namepattern})");
        assertThat(countSql).contains("status = #{status}");
        assertThat(count.getAnnotation(InterceptorIgnore.class)).isNull();
    }

    @Test
    void updateAndSoftDeleteAreTenantScopedAndVersionGuarded() throws Exception {
        Method update = method(KnowledgeBaseMapper.class, "updateConfig");
        String updateSql = sql(update);
        assertThat(updateSql).contains("tenant_id = #{tenantid}", "deleted_at is null",
                "version = #{version}", "version = version + 1");
        assertThat(update.getAnnotation(InterceptorIgnore.class)).isNull();

        Method delete = method(KnowledgeBaseMapper.class, "softDelete");
        String deleteSql = sql(delete);
        assertThat(deleteSql).contains("tenant_id = #{tenantid}", "deleted_at is null",
                "version = #{version}", "status = 'DELETED'".toLowerCase());
        assertThat(delete.getAnnotation(InterceptorIgnore.class)).isNull();
    }

    @Test
    void fileReferenceCheckIsTenantAndKnowledgeBaseScoped() throws Exception {
        Method method = method(KnowledgeFileReferenceMapper.class, "countActiveFiles");
        String sql = sql(method);
        assertThat(sql).contains("from knowledge_files",
                "tenant_id = #{tenantid}", "knowledge_base_id = #{knowledgebaseid}", "deleted_at is null");
        assertThat(method.getAnnotation(InterceptorIgnore.class)).isNull();
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
