package com.knowagent.knowledge.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeModelProviderReferenceMapperSqlContractTest {

    @Test
    void referenceCheckIsOwnedByKnowledgeAndExplicitlyTenantScoped() throws Exception {
        Method method = KnowledgeModelProviderReferenceMapper.class.getDeclaredMethod(
                "countActiveReferences", java.util.UUID.class, java.util.UUID.class);
        String sql = String.join(" ", Arrays.stream(method.getAnnotation(Select.class).value())
                        .map(String::strip).toList())
                .replaceAll("\\s+", " ")
                .toLowerCase();

        assertThat(sql).contains(
                "from knowledge_bases",
                "tenant_id = #{tenantid}",
                "deleted_at is null",
                "embedding_provider_id = #{providerid}",
                "rerank_provider_id = #{providerid}");
    }
}
