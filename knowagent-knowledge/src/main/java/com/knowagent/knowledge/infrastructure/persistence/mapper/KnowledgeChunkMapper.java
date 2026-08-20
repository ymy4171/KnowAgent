package com.knowagent.knowledge.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.knowagent.knowledge.infrastructure.persistence.entity.KnowledgeChunkPo;
import com.knowagent.knowledge.infrastructure.persistence.entity.KnowledgeRetrievalChunkPo;
import com.knowagent.knowledge.chunk.ChunkIndexStatus;
import com.knowagent.knowledge.infrastructure.persistence.typehandler.StringListJsonbTypeHandler;
import com.knowagent.knowledge.infrastructure.persistence.typehandler.StringMapJsonbTypeHandler;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@code knowledge_chunks}.
 *
 * <p>Every statement runs inside an authenticated request (or a worker transaction) and
 * deliberately stays under the tenant-line plugin: each custom SQL carries an explicit
 * {@code tenant_id} (plus {@code knowledge_base_id} and {@code file_id}) condition, and
 * the interceptor injects the context tenant as a fail-closed backstop. Deletion and
 * reads are scoped to the exact {@code (tenant, knowledge base, file)} triple, so a bare
 * file UUID can never be used to touch another tenant's chunks. Rows are never soft
 * deleted: chunks are rebuildable and are replaced wholesale for their file.
 */
@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunkPo> {

    String COLUMNS = """
            id, tenant_id, knowledge_base_id, file_id, chunk_index, content, content_hash,
            token_count, start_char_offset, end_char_offset, start_token_offset, end_token_offset,
            page_number, section_path, metadata, index_status, embedding_model_spec, error_code,
            error_message, version, created_at, updated_at
            """;

    @Results(id = "knowledgeChunkPoResultMap", value = {
            @Result(column = "section_path", property = "sectionPath",
                    typeHandler = StringListJsonbTypeHandler.class),
            @Result(column = "metadata", property = "metadata",
                    typeHandler = StringMapJsonbTypeHandler.class)
    })
    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM knowledge_chunks
            WHERE tenant_id = #{tenantId} AND knowledge_base_id = #{knowledgeBaseId} AND file_id = #{fileId}
            ORDER BY chunk_index
            """)
    List<KnowledgeChunkPo> selectByFile(@Param("tenantId") UUID tenantId,
                                        @Param("knowledgeBaseId") UUID knowledgeBaseId,
                                        @Param("fileId") UUID fileId);

    /**
     * Bulk-hydrates Milvus candidate ids from PostgreSQL. The id list contains only
     * typed UUIDs decoded by the vector adapter, while tenant and knowledge-base
     * predicates are explicit for both joined tenant tables. Lifecycle fields are
     * returned rather than trusted implicitly so the application can discard stale
     * vector hits after this query.
     */
    @Results(id = "knowledgeRetrievalChunkPoResultMap", value = {
            @Result(column = "section_path", property = "sectionPath",
                    typeHandler = StringListJsonbTypeHandler.class)
    })
    @Select({
            "<script>",
            "SELECT c.id AS chunk_id, c.tenant_id, c.knowledge_base_id, c.file_id,",
            "       f.display_name, c.content, c.page_number, c.section_path, c.index_status,",
            "       f.status AS file_status, f.deleted_at AS file_deleted_at",
            "FROM knowledge_chunks c",
            "JOIN knowledge_files f ON f.tenant_id = c.tenant_id",
            " AND f.knowledge_base_id = c.knowledge_base_id AND f.id = c.file_id",
            "WHERE c.tenant_id = #{tenantId} AND c.knowledge_base_id = #{knowledgeBaseId}",
            "  AND f.tenant_id = #{tenantId} AND f.knowledge_base_id = #{knowledgeBaseId}",
            "  AND c.id IN",
            "<foreach collection='chunkIds' item='chunkId' open='(' separator=',' close=')'>",
            "#{chunkId}",
            "</foreach>",
            "</script>"
    })
    List<KnowledgeRetrievalChunkPo> selectRetrievalChunks(@Param("tenantId") UUID tenantId,
                                                           @Param("knowledgeBaseId") UUID knowledgeBaseId,
                                                           @Param("chunkIds") List<UUID> chunkIds);

    @Delete("""
            DELETE FROM knowledge_chunks
            WHERE tenant_id = #{tenantId} AND knowledge_base_id = #{knowledgeBaseId} AND file_id = #{fileId}
            """)
    int deleteByFile(@Param("tenantId") UUID tenantId,
                     @Param("knowledgeBaseId") UUID knowledgeBaseId,
                     @Param("fileId") UUID fileId);

    @Update("""
            UPDATE knowledge_chunks
            SET index_status = #{targetStatus},
                embedding_model_spec = #{embeddingModelSpec},
                error_code = #{errorCode},
                error_message = #{errorMessage},
                updated_at = CURRENT_TIMESTAMP,
                version = version + 1
            WHERE tenant_id = #{tenantId}
              AND knowledge_base_id = #{knowledgeBaseId}
              AND file_id = #{fileId}
              AND index_status = #{expectedStatus}
            """)
    int transitionIndexStatus(@Param("tenantId") UUID tenantId,
                              @Param("knowledgeBaseId") UUID knowledgeBaseId,
                              @Param("fileId") UUID fileId,
                              @Param("expectedStatus") ChunkIndexStatus expectedStatus,
                              @Param("targetStatus") ChunkIndexStatus targetStatus,
                              @Param("embeddingModelSpec") String embeddingModelSpec,
                              @Param("errorCode") String errorCode,
                              @Param("errorMessage") String errorMessage);
}
