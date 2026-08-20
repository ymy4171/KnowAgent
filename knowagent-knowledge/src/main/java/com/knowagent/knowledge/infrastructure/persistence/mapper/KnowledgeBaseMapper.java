package com.knowagent.knowledge.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.knowagent.knowledge.chunk.ChunkPolicy;
import com.knowagent.knowledge.infrastructure.persistence.entity.KnowledgeBasePo;
import com.knowagent.knowledge.infrastructure.persistence.typehandler.ChunkPolicyJsonbTypeHandler;
import com.knowagent.knowledge.infrastructure.persistence.typehandler.RetrievalConfigJsonbTypeHandler;
import com.knowagent.knowledge.knowledgebase.KnowledgeBaseStatus;
import com.knowagent.knowledge.knowledgebase.KnowledgeType;
import com.knowagent.knowledge.knowledgebase.RetrievalConfig;
import com.knowagent.security.infrastructure.persistence.typehandler.JsonNodeJsonbTypeHandler;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@code knowledge_bases}.
 *
 * <p>Every statement here runs inside an authenticated request, so it deliberately
 * stays under the tenant-line plugin: each custom SQL carries an explicit
 * {@code tenant_id} condition and the interceptor additionally injects the context
 * tenant as a fail-closed backstop (the same pattern as the user-management and
 * model-provider queries). No method bypasses the tenant line.
 */
@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBasePo> {

    String COLUMNS = """
            id, tenant_id, slug, name, description, knowledge_type, status, embedding_provider_id,
            embedding_model, rerank_provider_id, rerank_model, chunk_policy, retrieval_config, metadata,
            created_by, updated_by, version, created_at, updated_at, deleted_at
            """;

    @Results(id = "knowledgeBasePoResultMap", value = {
            @Result(column = "chunk_policy", property = "chunkPolicy",
                    typeHandler = ChunkPolicyJsonbTypeHandler.class),
            @Result(column = "retrieval_config", property = "retrievalConfig",
                    typeHandler = RetrievalConfigJsonbTypeHandler.class),
            @Result(column = "metadata", property = "metadata",
                    typeHandler = JsonNodeJsonbTypeHandler.class)
    })
    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM knowledge_bases
            WHERE tenant_id = #{tenantId} AND id = #{id} AND deleted_at IS NULL
            LIMIT 1
            """)
    KnowledgeBasePo selectByIdAndTenant(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    @ResultMap("knowledgeBasePoResultMap")
    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM knowledge_bases
            WHERE tenant_id = #{tenantId} AND id = #{id} AND deleted_at IS NULL
            FOR UPDATE
            """)
    KnowledgeBasePo selectByIdAndTenantForUpdate(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    @ResultMap("knowledgeBasePoResultMap")
    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM knowledge_bases
            WHERE tenant_id = #{tenantId} AND id = #{id} AND deleted_at IS NULL
            FOR KEY SHARE
            """)
    KnowledgeBasePo selectByIdAndTenantForKeyShare(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    @ResultMap("knowledgeBasePoResultMap")
    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM knowledge_bases
            WHERE tenant_id = #{tenantId} AND slug = #{slug} AND deleted_at IS NULL
            LIMIT 1
            """)
    KnowledgeBasePo selectActiveBySlug(@Param("tenantId") UUID tenantId, @Param("slug") String slug);

    @ResultMap("knowledgeBasePoResultMap")
    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM knowledge_bases
            WHERE tenant_id = #{tenantId} AND deleted_at IS NULL
              AND (COALESCE(#{namePattern}, '') = '' OR LOWER(name) LIKE LOWER(#{namePattern}) ESCAPE '\\')
              AND (COALESCE(#{slugPattern}, '') = '' OR LOWER(slug) LIKE LOWER(#{slugPattern}) ESCAPE '\\')
              AND (COALESCE(#{status}, '') = '' OR status = #{status})
            ORDER BY created_at DESC, id
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<KnowledgeBasePo> selectPage(@Param("tenantId") UUID tenantId,
                                     @Param("namePattern") String namePattern,
                                     @Param("slugPattern") String slugPattern,
                                     @Param("status") KnowledgeBaseStatus status,
                                     @Param("limit") int limit,
                                     @Param("offset") int offset);

    @Select("""
            SELECT COUNT(*)
            FROM knowledge_bases
            WHERE tenant_id = #{tenantId} AND deleted_at IS NULL
              AND (COALESCE(#{namePattern}, '') = '' OR LOWER(name) LIKE LOWER(#{namePattern}) ESCAPE '\\')
              AND (COALESCE(#{slugPattern}, '') = '' OR LOWER(slug) LIKE LOWER(#{slugPattern}) ESCAPE '\\')
              AND (COALESCE(#{status}, '') = '' OR status = #{status})
            """)
    long countAll(@Param("tenantId") UUID tenantId,
                  @Param("namePattern") String namePattern,
                  @Param("slugPattern") String slugPattern,
                  @Param("status") KnowledgeBaseStatus status);

    @Update("""
            UPDATE knowledge_bases
            SET slug = #{slug},
                name = #{name},
                description = #{description},
                knowledge_type = #{knowledgeType},
                status = #{status},
                embedding_provider_id = #{embeddingProviderId},
                embedding_model = #{embeddingModel},
                rerank_provider_id = #{rerankProviderId},
                rerank_model = #{rerankModel},
                chunk_policy = #{chunkPolicy, typeHandler=com.knowagent.knowledge.infrastructure.persistence.typehandler.ChunkPolicyJsonbTypeHandler},
                retrieval_config = #{retrievalConfig, typeHandler=com.knowagent.knowledge.infrastructure.persistence.typehandler.RetrievalConfigJsonbTypeHandler},
                metadata = #{metadata, typeHandler=com.knowagent.security.infrastructure.persistence.typehandler.JsonNodeJsonbTypeHandler},
                updated_by = #{updatedBy},
                updated_at = CURRENT_TIMESTAMP,
                version = version + 1
            WHERE tenant_id = #{tenantId}
              AND id = #{id}
              AND deleted_at IS NULL
              AND version = #{version}
            """)
    int updateConfig(@Param("tenantId") UUID tenantId,
                     @Param("id") UUID id,
                     @Param("slug") String slug,
                     @Param("name") String name,
                     @Param("description") String description,
                     @Param("knowledgeType") KnowledgeType knowledgeType,
                     @Param("status") KnowledgeBaseStatus status,
                     @Param("embeddingProviderId") UUID embeddingProviderId,
                     @Param("embeddingModel") String embeddingModel,
                     @Param("rerankProviderId") UUID rerankProviderId,
                     @Param("rerankModel") String rerankModel,
                     @Param("chunkPolicy") ChunkPolicy chunkPolicy,
                     @Param("retrievalConfig") RetrievalConfig retrievalConfig,
                     @Param("metadata") JsonNode metadata,
                     @Param("updatedBy") UUID updatedBy,
                     @Param("version") long version);

    @Update("""
            UPDATE knowledge_bases
            SET status = 'DELETED',
                deleted_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP,
                version = version + 1
            WHERE tenant_id = #{tenantId}
              AND id = #{id}
              AND deleted_at IS NULL
              AND version = #{version}
            """)
    int softDelete(@Param("tenantId") UUID tenantId,
                   @Param("id") UUID id,
                   @Param("version") long version);
}
