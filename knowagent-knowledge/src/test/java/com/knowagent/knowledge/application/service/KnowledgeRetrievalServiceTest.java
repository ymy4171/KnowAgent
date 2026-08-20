package com.knowagent.knowledge.application.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.knowledge.application.port.out.KnowledgeBaseRepository;
import com.knowagent.knowledge.application.port.out.KnowledgeFileRepository;
import com.knowagent.knowledge.application.port.out.KnowledgeRetrievalObserver;
import com.knowagent.knowledge.application.port.out.KnowledgeRetrievalRepository;
import com.knowagent.knowledge.application.port.out.RetrievalChunkRecord;
import com.knowagent.knowledge.chunk.ChunkIndexStatus;
import com.knowagent.knowledge.chunk.ChunkPolicy;
import com.knowagent.knowledge.file.KnowledgeFile;
import com.knowagent.knowledge.file.KnowledgeFileStatus;
import com.knowagent.knowledge.knowledgebase.KnowledgeBase;
import com.knowagent.knowledge.knowledgebase.KnowledgeBaseStatus;
import com.knowagent.knowledge.knowledgebase.KnowledgeType;
import com.knowagent.knowledge.knowledgebase.RetrievalConfig;
import com.knowagent.knowledge.vector.VectorHit;
import com.knowagent.knowledge.vector.VectorQuery;
import com.knowagent.knowledge.vector.VectorStoreGateway;
import com.knowagent.model.embedding.EmbeddingGateway;
import com.knowagent.model.embedding.EmbeddingRequest;
import com.knowagent.model.embedding.EmbeddingResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeRetrievalServiceTest {

    private final TenantId tenant = TenantId.of(UUID.randomUUID());
    private final UUID knowledgeBaseId = UUID.randomUUID();
    private final UUID providerId = UUID.randomUUID();

    private KnowledgeBaseRepository knowledgeBases;
    private KnowledgeFileRepository files;
    private KnowledgeRetrievalRepository retrievalRepository;
    private EmbeddingGateway embeddingGateway;
    private VectorStoreGateway vectorStoreGateway;
    private KnowledgeRetrievalObserver observer;
    private KnowledgeRetrievalService service;

    @BeforeEach
    void setUp() {
        knowledgeBases = mock(KnowledgeBaseRepository.class);
        files = mock(KnowledgeFileRepository.class);
        retrievalRepository = mock(KnowledgeRetrievalRepository.class);
        embeddingGateway = mock(EmbeddingGateway.class);
        vectorStoreGateway = mock(VectorStoreGateway.class);
        observer = mock(KnowledgeRetrievalObserver.class);
        service = new KnowledgeRetrievalService(knowledgeBases, files, retrievalRepository,
                embeddingGateway, vectorStoreGateway, observer);
    }

    @Test
    void queryEmbeddingMilvusPostgresCitationOrderAndMappingAreStable() {
        UUID requestedFile = UUID.randomUUID();
        UUID misleadingMilvusFile = UUID.randomUUID();
        UUID chunk1 = UUID.randomUUID();
        UUID chunk2 = UUID.randomUUID();
        UUID missingChunk = UUID.randomUUID();
        when(knowledgeBases.findById(tenant, knowledgeBaseId))
                .thenReturn(Optional.of(knowledgeBase(new RetrievalConfig(4, 0.5, false))));
        when(files.findById(tenant, knowledgeBaseId, requestedFile))
                .thenReturn(Optional.of(file(requestedFile, KnowledgeFileStatus.READY)));
        when(embeddingGateway.embed(any())).thenReturn(embedding());
        when(vectorStoreGateway.search(any())).thenReturn(List.of(
                new VectorHit(chunk1, misleadingMilvusFile, null, 0.91),
                new VectorHit(missingChunk, misleadingMilvusFile, null, 0.89),
                new VectorHit(chunk1, misleadingMilvusFile, null, 0.99),
                new VectorHit(chunk2, misleadingMilvusFile, null, 0.72)));
        // PostgreSQL may return rows in any order; Milvus order must be restored.
        when(retrievalRepository.findByChunkIds(eq(tenant), eq(knowledgeBaseId), anyList()))
                .thenReturn(List.of(
                        row(chunk2, tenant, knowledgeBaseId, requestedFile, "b.txt", "second", 7,
                                List.of("2"), ChunkIndexStatus.READY, KnowledgeFileStatus.READY, null),
                        row(chunk1, tenant, knowledgeBaseId, requestedFile, "a.pdf", "first", 3,
                                List.of("1", "1.1"), ChunkIndexStatus.READY, KnowledgeFileStatus.READY, null)));

        KnowledgeRetrievalResult result = service.retrieve(new KnowledgeRetrievalCommand(
                tenant, knowledgeBaseId, "  safe query  ", null, null, List.of(requestedFile)));

        assertThat(result.citations()).extracting(KnowledgeCitation::chunkId)
                .containsExactly(chunk1, chunk2);
        assertThat(result.citations()).extracting(KnowledgeCitation::rank).containsExactly(1, 2);
        KnowledgeCitation first = result.citations().getFirst();
        assertThat(first.fileId()).isEqualTo(requestedFile);
        assertThat(first.displayName()).isEqualTo("a.pdf");
        assertThat(first.content()).isEqualTo("first");
        assertThat(first.pageNumber()).isEqualTo(3);
        assertThat(first.sectionPath()).containsExactly("1", "1.1");
        assertThat(first.score()).isEqualTo(0.91);

        ArgumentCaptor<EmbeddingRequest> embeddingRequest = ArgumentCaptor.forClass(EmbeddingRequest.class);
        verify(embeddingGateway).embed(embeddingRequest.capture());
        assertThat(embeddingRequest.getValue().texts()).containsExactly("safe query");
        assertThat(embeddingRequest.getValue().tenantId()).isEqualTo(tenant);
        assertThat(embeddingRequest.getValue().providerId()).isEqualTo(providerId);

        ArgumentCaptor<VectorQuery> vectorQuery = ArgumentCaptor.forClass(VectorQuery.class);
        verify(vectorStoreGateway).search(vectorQuery.capture());
        assertThat(vectorQuery.getValue().tenantId()).isEqualTo(tenant);
        assertThat(vectorQuery.getValue().knowledgeBaseId()).isEqualTo(knowledgeBaseId);
        assertThat(vectorQuery.getValue().topK()).isEqualTo(4);
        assertThat(vectorQuery.getValue().minimumScore()).isEqualTo(-1.0);
        assertThat(vectorQuery.getValue().fileIds()).containsExactly(requestedFile);

        InOrder order = inOrder(knowledgeBases, files, embeddingGateway, vectorStoreGateway, retrievalRepository);
        order.verify(knowledgeBases).findById(tenant, knowledgeBaseId);
        order.verify(files).findById(tenant, knowledgeBaseId, requestedFile);
        order.verify(embeddingGateway).embed(any());
        order.verify(vectorStoreGateway).search(any());
        order.verify(retrievalRepository).findByChunkIds(eq(tenant), eq(knowledgeBaseId), anyList());
    }

    @Test
    void crossTenantOrUnknownRequestedFileFailsBeforeExternalCalls() {
        UUID forgedFile = UUID.randomUUID();
        when(knowledgeBases.findById(tenant, knowledgeBaseId))
                .thenReturn(Optional.of(knowledgeBase(RetrievalConfig.defaults())));
        when(files.findById(tenant, knowledgeBaseId, forgedFile)).thenReturn(Optional.empty());

        assertError(ErrorCode.RESOURCE_NOT_FOUND, () -> service.retrieve(new KnowledgeRetrievalCommand(
                tenant, knowledgeBaseId, "query", null, null, List.of(forgedFile))));

        verify(embeddingGateway, never()).embed(any());
        verify(vectorStoreGateway, never()).search(any());
    }

    @Test
    void requestedFileMustBeReady() {
        UUID fileId = UUID.randomUUID();
        when(knowledgeBases.findById(tenant, knowledgeBaseId))
                .thenReturn(Optional.of(knowledgeBase(RetrievalConfig.defaults())));
        when(files.findById(tenant, knowledgeBaseId, fileId))
                .thenReturn(Optional.of(file(fileId, KnowledgeFileStatus.INDEXING)));

        assertError(ErrorCode.CONFLICT, () -> service.retrieve(new KnowledgeRetrievalCommand(
                tenant, knowledgeBaseId, "query", null, null, List.of(fileId))));
        verify(embeddingGateway, never()).embed(any());
    }

    @Test
    void onlyAuthoritativeReadyRowsSurviveThresholdAndTopK() {
        UUID ready1 = UUID.randomUUID();
        UUID wrongTenant = UUID.randomUUID();
        UUID failedChunk = UUID.randomUUID();
        UUID failedFile = UUID.randomUUID();
        UUID deletedFile = UUID.randomUUID();
        UUID belowThreshold = UUID.randomUUID();
        UUID ready2 = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        when(knowledgeBases.findById(tenant, knowledgeBaseId))
                .thenReturn(Optional.of(knowledgeBase(new RetrievalConfig(7, 0.6, false))));
        when(embeddingGateway.embed(any())).thenReturn(embedding());
        when(vectorStoreGateway.search(any())).thenReturn(List.of(
                hit(ready1, 0.95), hit(wrongTenant, 0.94), hit(failedChunk, 0.93),
                hit(failedFile, 0.92), hit(deletedFile, 0.91), hit(belowThreshold, 0.59), hit(ready2, 0.80)));
        when(retrievalRepository.findByChunkIds(eq(tenant), eq(knowledgeBaseId), anyList()))
                .thenReturn(List.of(
                        readyRow(ready2, fileId, "two"),
                        readyRow(ready1, fileId, "one"),
                        row(wrongTenant, TenantId.of(UUID.randomUUID()), knowledgeBaseId, fileId, "x", "x", null,
                                List.of(), ChunkIndexStatus.READY, KnowledgeFileStatus.READY, null),
                        row(failedChunk, tenant, knowledgeBaseId, fileId, "x", "x", null,
                                List.of(), ChunkIndexStatus.FAILED, KnowledgeFileStatus.READY, null),
                        row(failedFile, tenant, knowledgeBaseId, fileId, "x", "x", null,
                                List.of(), ChunkIndexStatus.READY, KnowledgeFileStatus.FAILED, null),
                        row(deletedFile, tenant, knowledgeBaseId, fileId, "x", "x", null,
                                List.of(), ChunkIndexStatus.READY, KnowledgeFileStatus.READY, Instant.now()),
                        readyRow(belowThreshold, fileId, "low")));

        KnowledgeRetrievalResult wider = service.retrieve(new KnowledgeRetrievalCommand(
                tenant, knowledgeBaseId, "query", 7, null, null));
        assertThat(wider.citations()).extracting(KnowledgeCitation::chunkId).containsExactly(ready1, ready2);
        assertThat(wider.citations()).extracting(KnowledgeCitation::rank).containsExactly(1, 2);
    }

    @Test
    void emptyMilvusResultIsSuccessfulAndDoesNotQueryPostgresChunks() {
        when(knowledgeBases.findById(tenant, knowledgeBaseId))
                .thenReturn(Optional.of(knowledgeBase(RetrievalConfig.defaults())));
        when(embeddingGateway.embed(any())).thenReturn(embedding());
        when(vectorStoreGateway.search(any())).thenReturn(List.of());
        doThrow(new IllegalStateException("metrics unavailable")).when(observer)
                .record(any(), any(), any(), anyInt(), anyInt(), any());

        KnowledgeRetrievalResult result = service.retrieve(new KnowledgeRetrievalCommand(
                tenant, knowledgeBaseId, "query", null, null, null));

        assertThat(result.citations()).isEmpty();
        verify(retrievalRepository, never()).findByChunkIds(any(), any(), anyList());
    }

    @Test
    void overridesAndDuplicateFileIdsAreControlled() {
        UUID fileId = UUID.randomUUID();
        when(knowledgeBases.findById(tenant, knowledgeBaseId))
                .thenReturn(Optional.of(knowledgeBase(new RetrievalConfig(10, 0.1, false))));
        when(files.findById(tenant, knowledgeBaseId, fileId))
                .thenReturn(Optional.of(file(fileId, KnowledgeFileStatus.READY)));
        when(embeddingGateway.embed(any())).thenReturn(embedding());
        when(vectorStoreGateway.search(any())).thenReturn(List.of());

        service.retrieve(new KnowledgeRetrievalCommand(
                tenant, knowledgeBaseId, "query", 3, 0.8, List.of(fileId, fileId)));

        verify(files).findById(tenant, knowledgeBaseId, fileId);
        ArgumentCaptor<VectorQuery> query = ArgumentCaptor.forClass(VectorQuery.class);
        verify(vectorStoreGateway).search(query.capture());
        assertThat(query.getValue().topK()).isEqualTo(3);
        assertThat(query.getValue().fileIds()).containsExactly(fileId);
    }

    @Test
    void enabledRerankIsExplicitlyRejectedWithoutInventingScores() {
        when(knowledgeBases.findById(tenant, knowledgeBaseId))
                .thenReturn(Optional.of(knowledgeBase(new RetrievalConfig(10, 0.0, true))));

        assertError(ErrorCode.MODEL_CONFIGURATION_ERROR, () -> service.retrieve(
                new KnowledgeRetrievalCommand(tenant, knowledgeBaseId, "query", null, null, null)));
        verify(embeddingGateway, never()).embed(any());
        verify(vectorStoreGateway, never()).search(any());
    }

    @Test
    void blankQueryAndInvalidOverridesAreStableValidationErrors() {
        assertError(ErrorCode.VALIDATION_ERROR, () -> service.retrieve(
                new KnowledgeRetrievalCommand(tenant, knowledgeBaseId, "  ", null, null, null)));
        when(knowledgeBases.findById(tenant, knowledgeBaseId))
                .thenReturn(Optional.of(knowledgeBase(RetrievalConfig.defaults())));
        assertError(ErrorCode.VALIDATION_ERROR, () -> service.retrieve(
                new KnowledgeRetrievalCommand(tenant, knowledgeBaseId, "query", 101, null, null)));
        assertError(ErrorCode.VALIDATION_ERROR, () -> service.retrieve(
                new KnowledgeRetrievalCommand(tenant, knowledgeBaseId, "query", null, Double.NaN, null)));
    }

    private KnowledgeBase knowledgeBase(RetrievalConfig retrievalConfig) {
        Instant now = Instant.now();
        return new KnowledgeBase(knowledgeBaseId, tenant, "docs", "Docs", null,
                KnowledgeType.LOCAL, KnowledgeBaseStatus.ACTIVE, providerId, "embedding-model",
                null, null, ChunkPolicy.defaults(), retrievalConfig, JsonNodeFactory.instance.objectNode(),
                UUID.randomUUID(), UUID.randomUUID(), 0, now, now, null);
    }

    private KnowledgeFile file(UUID id, KnowledgeFileStatus status) {
        Instant now = Instant.now();
        return new KnowledgeFile(id, tenant, knowledgeBaseId, null, null, "file.txt", "file.txt",
                "tenants/opaque/source", "text/plain", "txt", "a".repeat(64), 4, status,
                1, 1, JsonNodeFactory.instance.objectNode(), JsonNodeFactory.instance.objectNode(),
                null, null, false, UUID.randomUUID(), UUID.randomUUID(), 0, now, now, null);
    }

    private EmbeddingResult embedding() {
        return new EmbeddingResult(List.of(new float[]{1f, 0f, 0f, 0f}), 4,
                "embedding-model", 1, 1);
    }

    private VectorHit hit(UUID chunkId, double score) {
        return new VectorHit(chunkId, UUID.randomUUID(), null, score);
    }

    private RetrievalChunkRecord readyRow(UUID chunkId, UUID fileId, String content) {
        return row(chunkId, tenant, knowledgeBaseId, fileId, "file.txt", content, 1,
                List.of("1"), ChunkIndexStatus.READY, KnowledgeFileStatus.READY, null);
    }

    private static RetrievalChunkRecord row(UUID chunkId, TenantId tenantId, UUID kb, UUID fileId,
                                            String displayName, String content, Integer page,
                                            List<String> sectionPath, ChunkIndexStatus chunkStatus,
                                            KnowledgeFileStatus fileStatus, Instant deletedAt) {
        return new RetrievalChunkRecord(chunkId, tenantId, kb, fileId, displayName, content,
                page, sectionPath, chunkStatus, fileStatus, deletedAt);
    }

    private static void assertError(ErrorCode expected, Runnable invocation) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(expected));
    }
}
