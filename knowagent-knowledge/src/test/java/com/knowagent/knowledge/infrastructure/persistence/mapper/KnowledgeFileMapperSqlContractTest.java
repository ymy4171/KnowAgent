package com.knowagent.knowledge.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the tenant-isolation contract for the knowledge-file mapper: every custom
 * statement carries an explicit {@code tenant_id} and {@code deleted_at IS NULL} for
 * live reads, and no method bypasses the tenant-line plugin. The idempotency lookup
 * deliberately does not filter {@code deleted_at}: replay semantics decide on the whole
 * upload history for a key.
 */
class KnowledgeFileMapperSqlContractTest {

    @Test
    void liveReadQueriesCarryExplicitTenantKnowledgeBaseAndId() throws Exception {
        Method method = method(KnowledgeFileMapper.class, "selectByIdAndTenant");
        String sql = sql(method);
        assertThat(sql).contains("from knowledge_files",
                "tenant_id = #{tenantid}", "knowledge_base_id = #{knowledgebaseid}",
                "id = #{id}", "deleted_at is null", "limit 1");
        assertThat(method.getAnnotation(InterceptorIgnore.class)).isNull();
    }

    @Test
    void workerReadAndLockAreExplicitlyTenantAndFileScoped() throws Exception {
        Method read = method(KnowledgeFileMapper.class, "selectByTenantAndId");
        assertThat(sql(read)).contains("from knowledge_files", "tenant_id = #{tenantid}",
                "id = #{id}", "deleted_at is null", "limit 1");
        assertThat(read.getAnnotation(InterceptorIgnore.class)).isNull();

        Method lock = method(KnowledgeFileMapper.class, "selectByTenantAndIdForUpdate");
        assertThat(sql(lock)).contains("from knowledge_files", "tenant_id = #{tenantid}",
                "id = #{id}", "deleted_at is null", "for update");
        assertThat(lock.getAnnotation(InterceptorIgnore.class)).isNull();
    }

    @Test
    void workerStatusTransitionIsTenantStatusAndVersionGuarded() throws Exception {
        Method transition = method(KnowledgeFileMapper.class, "transitionStatus");
        assertThat(sql(transition)).contains("update knowledge_files",
                "status = #{targetstatus}", "error_code = #{errorcode}",
                "tenant_id = #{tenantid}", "knowledge_base_id = #{knowledgebaseid}",
                "id = #{fileid}", "status = #{expectedstatus}", "version = #{version}");
        assertThat(transition.getAnnotation(InterceptorIgnore.class)).isNull();
    }

    @Test
    void idempotencyLookupIsTenantScopedButSeesDeletedRows() throws Exception {
        Method method = method(KnowledgeFileMapper.class, "selectByUploadIdempotencyKey");
        String sql = sql(method);
        assertThat(sql).contains("from knowledge_files",
                "tenant_id = #{tenantid}", "knowledge_base_id = #{knowledgebaseid}",
                "upload_idempotency_key = #{uploadidempotencykey}", "order by created_at desc");
        // The row columns still include deleted_at, but there must be no deleted_at filter:
        // the idempotency history must be visible across soft-deletes.
        assertThat(sql).doesNotContain("deleted_at is null");
        assertThat(method.getAnnotation(InterceptorIgnore.class)).isNull();
    }

    @Test
    void chunkReplacementLockIsTenantScopedAndTakesForUpdate() throws Exception {
        Method method = method(KnowledgeFileMapper.class, "selectByIdAndTenantForUpdate");
        String sql = sql(method);
        assertThat(sql).contains("from knowledge_files", "tenant_id = #{tenantid}",
                "knowledge_base_id = #{knowledgebaseid}", "id = #{id}", "deleted_at is null",
                "for update");
        assertThat(method.getAnnotation(InterceptorIgnore.class)).isNull();
    }

    @Test
    void chunkStatisticsUpdateIsVersionGuardedAndTenantScoped() throws Exception {
        Method method = method(KnowledgeFileMapper.class, "updateChunkStatistics");
        String sql = sql(method);
        assertThat(sql).contains("update knowledge_files", "chunk_count = #{chunkcount}",
                "token_count = #{tokencount}", "version = version + 1",
                "tenant_id = #{tenantid}", "knowledge_base_id = #{knowledgebaseid}",
                "id = #{fileid}", "deleted_at is null", "version = #{version}");
        assertThat(method.getAnnotation(InterceptorIgnore.class)).isNull();
    }

    @Test
    void pageAndCountShareTheSameFilterAndPageIsOrdered() throws Exception {
        Method page = method(KnowledgeFileMapper.class, "selectPage");
        String pageSql = sql(page);
        assertThat(pageSql).contains("from knowledge_files", "tenant_id = #{tenantid}",
                "knowledge_base_id = #{knowledgebaseid}", "deleted_at is null",
                "status = #{status}", "order by created_at desc", "limit #{limit}", "offset #{offset}");
        assertThat(page.getAnnotation(InterceptorIgnore.class)).isNull();

        Method count = method(KnowledgeFileMapper.class, "countAll");
        String countSql = sql(count);
        assertThat(countSql).contains("from knowledge_files", "tenant_id = #{tenantid}",
                "knowledge_base_id = #{knowledgebaseid}", "deleted_at is null", "status = #{status}");
        assertThat(count.getAnnotation(InterceptorIgnore.class)).isNull();
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
