package com.knowagent.knowledge.infrastructure.vector;

import com.knowagent.common.error.ErrorCode;
import com.knowagent.knowledge.vector.VectorHit;
import io.milvus.v2.service.vector.response.SearchResp;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Locks the search-result contract: only id, file-id scalar and score cross the
 * boundary (content stays null - PostgreSQL re-hydrates it), and a malformed
 * response fails closed with VECTOR_BAD_RESPONSE instead of yielding a broken hit.
 */
class MilvusSearchResultMapperTest {

    @Test
    void mapsIdFileIdAndScoreAndLeavesContentForPostgres() {
        UUID chunkId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        SearchResp.SearchResult result = SearchResp.SearchResult.builder()
                .id(chunkId.toString())
                .score(0.87f)
                .entity(Map.of(MilvusVectorEntityMapper.FIELD_FILE_ID, fileId.toString(),
                        MilvusVectorEntityMapper.FIELD_CHUNK_ID, chunkId.toString()))
                .build();

        List<VectorHit> hits = MilvusSearchResultMapper.toHits(List.of(result));

        assertThat(hits).hasSize(1);
        VectorHit hit = hits.get(0);
        assertThat(hit.chunkId()).isEqualTo(chunkId);
        assertThat(hit.fileId()).isEqualTo(fileId);
        assertThat(hit.score()).isCloseTo(0.87d, org.assertj.core.data.Offset.offset(1e-6d));
        assertThat(hit.content()).isNull();
    }

    @Test
    void aMissingOrNonUuidChunkIdIsABadResponse() {
        SearchResp.SearchResult missing = SearchResp.SearchResult.builder()
                .score(0.5f)
                .entity(Map.of(MilvusVectorEntityMapper.FIELD_FILE_ID, UUID.randomUUID().toString()))
                .build();
        assertBadResponse(() -> MilvusSearchResultMapper.toHits(List.of(missing)));

        SearchResp.SearchResult notAUuid = SearchResp.SearchResult.builder()
                .id("not-a-uuid")
                .score(0.5f)
                .entity(Map.of(MilvusVectorEntityMapper.FIELD_FILE_ID, UUID.randomUUID().toString()))
                .build();
        assertBadResponse(() -> MilvusSearchResultMapper.toHits(List.of(notAUuid)));
    }

    @Test
    void aMissingScoreOrFileIdIsABadResponse() {
        SearchResp.SearchResult noScore = SearchResp.SearchResult.builder()
                .id(UUID.randomUUID().toString())
                .entity(Map.of(MilvusVectorEntityMapper.FIELD_FILE_ID, UUID.randomUUID().toString()))
                .build();
        assertBadResponse(() -> MilvusSearchResultMapper.toHits(List.of(noScore)));

        SearchResp.SearchResult noFileId = SearchResp.SearchResult.builder()
                .id(UUID.randomUUID().toString())
                .score(0.5f)
                .entity(Map.of())
                .build();
        assertBadResponse(() -> MilvusSearchResultMapper.toHits(List.of(noFileId)));

        SearchResp.SearchResult illegalFileId = SearchResp.SearchResult.builder()
                .id(UUID.randomUUID().toString())
                .score(0.5f)
                .entity(Map.of(MilvusVectorEntityMapper.FIELD_FILE_ID, "nope"))
                .build();
        assertBadResponse(() -> MilvusSearchResultMapper.toHits(List.of(illegalFileId)));
    }

    @Test
    void aNonFiniteScoreIsABadResponse() {
        SearchResp.SearchResult nan = SearchResp.SearchResult.builder()
                .id(UUID.randomUUID().toString())
                .score(Float.NaN)
                .entity(Map.of(MilvusVectorEntityMapper.FIELD_FILE_ID, UUID.randomUUID().toString()))
                .build();
        assertBadResponse(() -> MilvusSearchResultMapper.toHits(List.of(nan)));
    }

    private static void assertBadResponse(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(VectorStoreException.class)
                .satisfies(e -> assertThat(((VectorStoreException) e).errorCode())
                        .isEqualTo(ErrorCode.VECTOR_BAD_RESPONSE));
    }
}
