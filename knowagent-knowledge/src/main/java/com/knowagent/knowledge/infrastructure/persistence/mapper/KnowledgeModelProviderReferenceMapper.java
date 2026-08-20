package com.knowagent.knowledge.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.UUID;

/** Knowledge-owned query used to guard model-provider deletion. */
@Mapper
public interface KnowledgeModelProviderReferenceMapper {

    @Select("""
            SELECT COUNT(*)
            FROM knowledge_bases
            WHERE tenant_id = #{tenantId}
              AND deleted_at IS NULL
              AND (embedding_provider_id = #{providerId} OR rerank_provider_id = #{providerId})
            """)
    long countActiveReferences(@Param("tenantId") UUID tenantId, @Param("providerId") UUID providerId);
}
