package com.knowagent.knowledge.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.knowagent.knowledge.file.KnowledgeFileStatus;
import com.knowagent.knowledge.infrastructure.persistence.entity.KnowledgeFilePo;
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
 * Persistence for {@code knowledge_files}.
 *
 * <p>Every statement here runs inside an authenticated request, so it deliberately
 * stays under the tenant-line plugin: each custom SQL carries an explicit
 * {@code tenant_id} condition and the interceptor additionally injects the context
 * tenant as a fail-closed backstop (the same pattern as the knowledge-base queries).
 * The idempotency lookup intentionally does not filter {@code deleted_at}: replay
 * semantics decide on the whole upload history for a key. The tenant-line plugin only
 * injects {@code tenant_id} on insert, so the {@code save} path is safe too.
 */
@Mapper
public interface KnowledgeFileMapper extends BaseMapper<KnowledgeFilePo> {

    String COLUMNS = """
            id, tenant_id, knowledge_base_id, parent_file_id, upload_idempotency_key, display_name,
            original_filename, object_key, content_type, file_extension, sha256, file_size_bytes,
            status, chunk_count, token_count, processing_params, metadata, error_code, error_message,
            retryable, created_by, updated_by, version, created_at, updated_at, deleted_at
            """;

    @Results(id = "knowledgeFilePoResultMap", value = {
            @Result(column = "processing_params", property = "processingParams",
                    typeHandler = JsonNodeJsonbTypeHandler.class),
            @Result(column = "metadata", property = "metadata",
                    typeHandler = JsonNodeJsonbTypeHandler.class)
    })
    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM knowledge_files
            WHERE tenant_id = #{tenantId} AND knowledge_base_id = #{knowledgeBaseId} AND id = #{id}
              AND deleted_at IS NULL
            LIMIT 1
            """)
    KnowledgeFilePo selectByIdAndTenant(@Param("tenantId") UUID tenantId,
                                        @Param("knowledgeBaseId") UUID knowledgeBaseId,
                                        @Param("id") UUID id);

    @ResultMap("knowledgeFilePoResultMap")
    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM knowledge_files
            WHERE tenant_id = #{tenantId} AND id = #{id} AND deleted_at IS NULL
            LIMIT 1
            """)
    KnowledgeFilePo selectByTenantAndId(@Param("tenantId") UUID tenantId,
                                        @Param("id") UUID id);

    @ResultMap("knowledgeFilePoResultMap")
    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM knowledge_files
            WHERE tenant_id = #{tenantId}
              AND knowledge_base_id = #{knowledgeBaseId}
              AND upload_idempotency_key = #{uploadIdempotencyKey}
            ORDER BY created_at DESC, id
            LIMIT 1
            """)
    KnowledgeFilePo selectByUploadIdempotencyKey(@Param("tenantId") UUID tenantId,
                                                 @Param("knowledgeBaseId") UUID knowledgeBaseId,
                                                 @Param("uploadIdempotencyKey") String uploadIdempotencyKey);

    /**
     * Locks the file row for the chunk-replacement transaction so concurrent retries of the
     * same {@code (tenant, knowledge base, file)} serialize; the lock is held until commit.
     */
    @ResultMap("knowledgeFilePoResultMap")
    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM knowledge_files
            WHERE tenant_id = #{tenantId} AND knowledge_base_id = #{knowledgeBaseId} AND id = #{id}
              AND deleted_at IS NULL
            FOR UPDATE
            """)
    KnowledgeFilePo selectByIdAndTenantForUpdate(@Param("tenantId") UUID tenantId,
                                                 @Param("knowledgeBaseId") UUID knowledgeBaseId,
                                                 @Param("id") UUID id);

    @ResultMap("knowledgeFilePoResultMap")
    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM knowledge_files
            WHERE tenant_id = #{tenantId} AND id = #{id} AND deleted_at IS NULL
            FOR UPDATE
            """)
    KnowledgeFilePo selectByTenantAndIdForUpdate(@Param("tenantId") UUID tenantId,
                                                 @Param("id") UUID id);

    @Update("""
            UPDATE knowledge_files
            SET status = #{targetStatus},
                error_code = #{errorCode},
                error_message = #{errorMessage},
                retryable = #{retryable},
                updated_at = CURRENT_TIMESTAMP,
                version = version + 1
            WHERE tenant_id = #{tenantId}
              AND knowledge_base_id = #{knowledgeBaseId}
              AND id = #{fileId}
              AND deleted_at IS NULL
              AND status = #{expectedStatus}
              AND version = #{version}
            """)
    int transitionStatus(@Param("tenantId") UUID tenantId,
                         @Param("knowledgeBaseId") UUID knowledgeBaseId,
                         @Param("fileId") UUID fileId,
                         @Param("expectedStatus") KnowledgeFileStatus expectedStatus,
                         @Param("targetStatus") KnowledgeFileStatus targetStatus,
                         @Param("errorCode") String errorCode,
                         @Param("errorMessage") String errorMessage,
                         @Param("retryable") boolean retryable,
                         @Param("version") long version);

    /**
     * Conditionally records the chunk statistics after a successful replacement: bumps the
     * version and only matches the exact version read under the row lock, so a lost update
     * surfaces as a zero row count (a {@code CONFLICT}) instead of silently overwriting
     * newer data.
     */
    @Update("""
            UPDATE knowledge_files
            SET chunk_count = #{chunkCount},
                token_count = #{tokenCount},
                version = version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE tenant_id = #{tenantId}
              AND knowledge_base_id = #{knowledgeBaseId}
              AND id = #{fileId}
              AND deleted_at IS NULL
              AND version = #{version}
            """)
    int updateChunkStatistics(@Param("tenantId") UUID tenantId,
                              @Param("knowledgeBaseId") UUID knowledgeBaseId,
                              @Param("fileId") UUID fileId,
                              @Param("chunkCount") int chunkCount,
                              @Param("tokenCount") long tokenCount,
                              @Param("version") long version);

    @ResultMap("knowledgeFilePoResultMap")
    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM knowledge_files
            WHERE tenant_id = #{tenantId} AND knowledge_base_id = #{knowledgeBaseId}
              AND deleted_at IS NULL
              AND (COALESCE(#{status}, '') = '' OR status = #{status})
            ORDER BY created_at DESC, id
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<KnowledgeFilePo> selectPage(@Param("tenantId") UUID tenantId,
                                     @Param("knowledgeBaseId") UUID knowledgeBaseId,
                                     @Param("status") KnowledgeFileStatus status,
                                     @Param("limit") int limit,
                                     @Param("offset") int offset);

    @Select("""
            SELECT COUNT(*)
            FROM knowledge_files
            WHERE tenant_id = #{tenantId} AND knowledge_base_id = #{knowledgeBaseId}
              AND deleted_at IS NULL
              AND (COALESCE(#{status}, '') = '' OR status = #{status})
            """)
    long countAll(@Param("tenantId") UUID tenantId,
                  @Param("knowledgeBaseId") UUID knowledgeBaseId,
                  @Param("status") KnowledgeFileStatus status);
}
