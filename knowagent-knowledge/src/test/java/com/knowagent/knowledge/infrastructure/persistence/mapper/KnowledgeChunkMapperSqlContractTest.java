package com.knowagent.knowledge.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the tenant-isolation contract for the chunk mapper: every custom statement carries
 * the explicit {@code tenant_id} + {@code knowledge_base_id} + {@code file_id} triple, so a
 * bare file UUID can never read or delete another tenant's chunks, and no method bypasses
 * the tenant-line plugin.
 */
class KnowledgeChunkMapperSqlContractTest {

    @Test
    void readIsScopedToExplicitTenantKnowledgeBaseAndFileTriple() throws Exception {
        Method method = method(KnowledgeChunkMapper.class, "selectByFile");
        String sql = sql(method);
        assertThat(sql).contains("from knowledge_chunks",
                "tenant_id = #{tenantid}", "knowledge_base_id = #{knowledgebaseid}",
                "file_id = #{fileid}", "order by chunk_index");
        assertThat(method.getAnnotation(InterceptorIgnore.class)).isNull();
    }

    @Test
    void deleteIsScopedToExplicitTenantKnowledgeBaseAndFileTriple() throws Exception {
        Method method = method(KnowledgeChunkMapper.class, "deleteByFile");
        String sql = sql(method);
        assertThat(sql).contains("delete from knowledge_chunks",
                "tenant_id = #{tenantid}", "knowledge_base_id = #{knowledgebaseid}",
                "file_id = #{fileid}");
        assertThat(method.getAnnotation(InterceptorIgnore.class)).isNull();
    }

    @Test
    void indexTransitionIsScopedAndExpectedStatusGuarded() throws Exception {
        Method method = method(KnowledgeChunkMapper.class, "transitionIndexStatus");
        String sql = sql(method);
        assertThat(sql).contains("update knowledge_chunks", "index_status = #{targetstatus}",
                "tenant_id = #{tenantid}", "knowledge_base_id = #{knowledgebaseid}",
                "file_id = #{fileid}", "index_status = #{expectedstatus}");
        assertThat(method.getAnnotation(InterceptorIgnore.class)).isNull();
    }

    @Test
    void retrievalHydrationBulkQueryScopesBothJoinedTablesAndReturnsLifecycleFacts() throws Exception {
        Method method = method(KnowledgeChunkMapper.class, "selectRetrievalChunks");
        String sql = sql(method);
        assertThat(sql).contains("from knowledge_chunks c", "join knowledge_files f",
                "c.tenant_id = #{tenantid}", "c.knowledge_base_id = #{knowledgebaseid}",
                "f.tenant_id = #{tenantid}", "f.knowledge_base_id = #{knowledgebaseid}",
                "c.id in", "collection='chunkids'", "#{chunkid}",
                "c.index_status", "f.status as file_status", "f.deleted_at as file_deleted_at");
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
                : method.isAnnotationPresent(Delete.class)
                ? method.getAnnotation(Delete.class).value()
                : method.getAnnotation(Update.class).value();
        return String.join(" ", Arrays.stream(value).map(String::strip).toList())
                .replaceAll("\\s+", " ")
                .toLowerCase();
    }
}
