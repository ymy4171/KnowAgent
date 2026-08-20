package com.knowagent.knowledge.application.service;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.knowledge.application.port.out.KnowledgeChunkRepository;
import com.knowagent.knowledge.chunk.ChunkDraft;
import com.knowagent.knowledge.chunk.Chunker;
import com.knowagent.knowledge.chunk.KnowledgeChunk;
import com.knowagent.knowledge.document.ParsedDocument;
import com.knowagent.knowledge.document.ParserRegistry;
import com.knowagent.knowledge.file.KnowledgeFile;
import com.knowagent.knowledge.file.KnowledgeFileStatus;
import com.knowagent.knowledge.knowledgebase.KnowledgeBase;
import com.knowagent.knowledge.vector.VectorStoreGateway;
import com.knowagent.model.embedding.EmbeddingGateway;
import com.knowagent.model.embedding.EmbeddingResult;
import com.knowagent.observability.task.Task;
import com.knowagent.workspace.storage.ObjectStorageGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeFileIngestionServiceTest {

    private final TenantId tenant = TenantId.of(UUID.randomUUID());
    private final UUID fileId = UUID.randomUUID();
    private final UUID knowledgeBaseId = UUID.randomUUID();
    private final Duration lease = Duration.ofMinutes(5);
    private final KnowledgeFileIngestionCommand command = new KnowledgeFileIngestionCommand(
            tenant, UUID.randomUUID(), "knowledge-file-ingestion", fileId, "b".repeat(64));

    private KnowledgeFileIngestionStateService states;
    private ObjectStorageGateway storage;
    private ParserRegistry parsers;
    private Chunker chunker;
    private ChunkWriteService chunkWriter;
    private KnowledgeChunkRepository chunks;
    private EmbeddingGateway embeddings;
    private VectorStoreGateway vectors;
    private KnowledgeFileIngestionService service;
    private Task task;
    private KnowledgeChunk chunk;

    @BeforeEach
    void setUp() {
        states = mock(KnowledgeFileIngestionStateService.class);
        storage = mock(ObjectStorageGateway.class);
        parsers = mock(ParserRegistry.class);
        chunker = mock(Chunker.class);
        chunkWriter = mock(ChunkWriteService.class);
        chunks = mock(KnowledgeChunkRepository.class);
        embeddings = mock(EmbeddingGateway.class);
        vectors = mock(VectorStoreGateway.class);
        service = new KnowledgeFileIngestionService(states, storage, parsers, chunker,
                chunkWriter, chunks, embeddings, vectors);

        KnowledgeFile file = mock(KnowledgeFile.class);
        KnowledgeBase kb = mock(KnowledgeBase.class);
        task = mock(Task.class);
        chunk = mock(KnowledgeChunk.class);
        when(file.originalFilename()).thenReturn("source.txt");
        when(file.contentType()).thenReturn("text/plain");
        when(file.fileSizeBytes()).thenReturn(12L);
        when(file.objectKey()).thenReturn(expectedKey());
        when(kb.id()).thenReturn(knowledgeBaseId);
        when(kb.embeddingProviderId()).thenReturn(UUID.randomUUID());
        when(kb.embeddingModel()).thenReturn("embedding-test");
        when(states.begin(command, "worker-a", lease))
                .thenReturn(KnowledgeFileIngestionStateService.StartResult.started(file, kb, task));
        when(states.advance(any(), any(), any(), any(), anyString(), anyInt(), any())).thenReturn(task);
        when(states.beginIndexing(any(), any(), anyInt(), anyString(), any())).thenReturn(task);
        when(storage.get(any())).thenReturn(new ByteArrayInputStream("hello world".getBytes()));
        when(parsers.parse(any())).thenReturn(mock(ParsedDocument.class));
        when(chunker.split(any(), any())).thenReturn(List.of(mock(ChunkDraft.class)));
        when(chunks.findByFile(tenant, knowledgeBaseId, fileId)).thenReturn(List.of(chunk));
        when(chunk.content()).thenReturn("hello world");
        when(chunk.id()).thenReturn(UUID.randomUUID());
        when(chunk.tenantId()).thenReturn(tenant);
        when(chunk.knowledgeBaseId()).thenReturn(knowledgeBaseId);
        when(chunk.fileId()).thenReturn(fileId);
        when(embeddings.embed(any())).thenReturn(new EmbeddingResult(
                List.of(new float[]{1F, 2F}), 2, "embedding-test", 1, 2));
    }

    @Test
    void executesTheFullStateSequenceAndCompensatesVectorsBeforeUpsert() {
        KnowledgeFileIngestionOutcome outcome = service.ingest(command, "worker-a", lease);

        assertThat(outcome).isEqualTo(KnowledgeFileIngestionOutcome.COMPLETED);
        InOrder stateOrder = inOrder(states);
        stateOrder.verify(states).begin(command, "worker-a", lease);
        stateOrder.verify(states).advance(command, task, KnowledgeFileStatus.PARSING,
                KnowledgeFileStatus.CHUNKING, "CHUNKING", 30, lease);
        stateOrder.verify(states).advance(command, task, KnowledgeFileStatus.CHUNKING,
                KnowledgeFileStatus.EMBEDDING, "EMBEDDING", 55, lease);
        stateOrder.verify(states).beginIndexing(command, task, 1, "embedding-test", lease);
        stateOrder.verify(states).complete(command, task, 1, "embedding-test");

        InOrder vectorOrder = inOrder(vectors);
        vectorOrder.verify(vectors).deleteByFile(tenant.value(), knowledgeBaseId, fileId);
        vectorOrder.verify(vectors).upsert(any());
        verify(chunkWriter).replaceChunks(any(), any(), any(), any());
    }

    @Test
    void corruptParsingIsPermanentAndDoesNotCallEmbeddingOrMilvus() {
        when(parsers.parse(any())).thenThrow(new BusinessException(
                ErrorCode.CORRUPT_DOCUMENT, "raw parser detail must not escape"));
        when(states.fail(any(), any(), any())).thenReturn(KnowledgeFileIngestionOutcome.TERMINAL_FAILURE);

        assertThat(service.ingest(command, "worker-a", lease))
                .isEqualTo(KnowledgeFileIngestionOutcome.TERMINAL_FAILURE);

        IngestionFailure failure = capturedFailure();
        assertThat(failure.errorCode()).isEqualTo(ErrorCode.CORRUPT_DOCUMENT);
        assertThat(failure.retryable()).isFalse();
        assertThat(failure.message()).doesNotContain("raw parser detail");
        verify(embeddings, never()).embed(any());
        verify(vectors, never()).upsert(any());
    }

    @Test
    void modelRateLimitIsRetryableAndNeverIndexes() {
        when(embeddings.embed(any())).thenThrow(new BusinessException(ErrorCode.MODEL_RATE_LIMITED, "429"));
        when(states.fail(any(), any(), any())).thenReturn(KnowledgeFileIngestionOutcome.DEFERRED);

        assertThat(service.ingest(command, "worker-a", lease)).isEqualTo(KnowledgeFileIngestionOutcome.DEFERRED);

        IngestionFailure failure = capturedFailure();
        assertThat(failure.errorCode()).isEqualTo(ErrorCode.MODEL_RATE_LIMITED);
        assertThat(failure.retryable()).isTrue();
        verify(vectors, never()).upsert(any());
    }

    @Test
    void milvusOutageIsRetryableAfterIdempotentDelete() {
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.VECTOR_UNAVAILABLE, "transport"))
                .when(vectors).upsert(any());
        when(states.fail(any(), any(), any())).thenReturn(KnowledgeFileIngestionOutcome.DEFERRED);

        assertThat(service.ingest(command, "worker-a", lease)).isEqualTo(KnowledgeFileIngestionOutcome.DEFERRED);

        IngestionFailure failure = capturedFailure();
        assertThat(failure.errorCode()).isEqualTo(ErrorCode.VECTOR_UNAVAILABLE);
        assertThat(failure.retryable()).isTrue();
        verify(vectors).deleteByFile(tenant.value(), knowledgeBaseId, fileId);
    }

    private IngestionFailure capturedFailure() {
        ArgumentCaptor<IngestionFailure> captor = ArgumentCaptor.forClass(IngestionFailure.class);
        verify(states).fail(any(), any(), captor.capture());
        return captor.getValue();
    }

    private String expectedKey() {
        return "tenants/" + tenant.value() + "/knowledge-bases/" + knowledgeBaseId
                + "/files/" + fileId + "/source";
    }
}
