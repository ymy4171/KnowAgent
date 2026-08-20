package com.knowagent.knowledge.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.UUID;

/**
 * Knowledge-owned query used to guard knowledge-base deletion. Reads the not-yet
 * implemented {@code knowledge_files} table directly (the file Mapper arrives with
 * 提示词十二); explicit {@code tenant_id} and {@code knowledge_base_id} keep the check
 * scoped.
 */
@Mapper
public interface KnowledgeFileReferenceMapper {

    @Select("""
            SELECT COUNT(*)
            FROM knowledge_files
            WHERE tenant_id = #{tenantId}
              AND knowledge_base_id = #{knowledgeBaseId}
              AND deleted_at IS NULL
            """)
    long countActiveFiles(@Param("tenantId") UUID tenantId,
                          @Param("knowledgeBaseId") UUID knowledgeBaseId);
}
