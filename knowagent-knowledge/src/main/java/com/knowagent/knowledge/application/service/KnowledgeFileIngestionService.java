package com.knowagent.knowledge.application.service;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.knowledge.application.port.out.KnowledgeChunkRepository;
import com.knowagent.knowledge.chunk.ChunkDraft;
import com.knowagent.knowledge.chunk.Chunker;
import com.knowagent.knowledge.chunk.KnowledgeChunk;
import com.knowagent.knowledge.document.ParseSource;
import com.knowagent.knowledge.document.ParsedDocument;
import com.knowagent.knowledge.document.ParserRegistry;
import com.knowagent.knowledge.file.KnowledgeFileStatus;
import com.knowagent.knowledge.knowledgebase.KnowledgeBase;
import com.knowagent.knowledge.vector.VectorChunk;
import com.knowagent.knowledge.vector.VectorStoreGateway;
import com.knowagent.model.embedding.EmbeddingGateway;
import com.knowagent.model.embedding.EmbeddingRequest;
import com.knowagent.model.embedding.EmbeddingResult;
import com.knowagent.observability.task.Task;
import com.knowagent.workspace.storage.GetObjectCommand;
import com.knowagent.workspace.storage.ObjectKey;
import com.knowagent.workspace.storage.ObjectStorageGateway;
import com.knowagent.workspace.storage.StorageKeys;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Executes the file-ingestion saga. External systems are intentionally outside a
 * database transaction; each PostgreSQL state slice is short and compensating
 * delete/upsert operations make retries converge.
 */
@Service
public class KnowledgeFileIngestionService {

    private final KnowledgeFileIngestionStateService states;
    private final ObjectStorageGateway storage;
    private final ParserRegistry parsers;
    private final Chunker chunker;
    private final ChunkWriteService chunkWriter;
    private final KnowledgeChunkRepository chunks;
    private final EmbeddingGateway embeddings;
    private final VectorStoreGateway vectors;

    public KnowledgeFileIngestionService(KnowledgeFileIngestionStateService states,
                                         ObjectStorageGateway storage,
                                         ParserRegistry parsers,
                                         Chunker chunker,
                                         ChunkWriteService chunkWriter,
                                         KnowledgeChunkRepository chunks,
                                         EmbeddingGateway embeddings,
                                         VectorStoreGateway vectors) {
        this.states = Objects.requireNonNull(states);
        this.storage = Objects.requireNonNull(storage);
        this.parsers = Objects.requireNonNull(parsers);
        this.chunker = Objects.requireNonNull(chunker);
        this.chunkWriter = Objects.requireNonNull(chunkWriter);
        this.chunks = Objects.requireNonNull(chunks);
        this.embeddings = Objects.requireNonNull(embeddings);
        this.vectors = Objects.requireNonNull(vectors);
    }

    public KnowledgeFileIngestionOutcome ingest(KnowledgeFileIngestionCommand command,
                                                String workerId,
                                                Duration taskLease) {
        var start = states.begin(command, workerId, taskLease);
        if (start.kind() == KnowledgeFileIngestionStateService.StartResult.Kind.ALREADY_PROCESSED) {
            return KnowledgeFileIngestionOutcome.ALREADY_PROCESSED;
        }
        if (start.kind() == KnowledgeFileIngestionStateService.StartResult.Kind.DEFERRED) {
            return KnowledgeFileIngestionOutcome.DEFERRED;
        }
        if (start.kind() == KnowledgeFileIngestionStateService.StartResult.Kind.TERMINAL) {
            return KnowledgeFileIngestionOutcome.TERMINAL_FAILURE;
        }

        Task task = start.task();
        try {
            KnowledgeBase kb = start.knowledgeBase();
            requireEmbeddingBinding(kb);
            ObjectKey expectedKey = StorageKeys.knowledgeFileSource(
                    command.tenantId(), kb.id(), command.fileId());
            if (!expectedKey.value().equals(start.file().objectKey())) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "The stored source location is invalid.");
            }

            ParsedDocument document;
            try (InputStream content = storage.get(new GetObjectCommand(command.tenantId(), expectedKey))) {
                document = parsers.parse(new ParseSource(
                        expectedKey.value(), start.file().originalFilename(), start.file().contentType(),
                        start.file().fileSizeBytes(), content));
            } catch (IOException closeFailure) {
                throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                        "The source document stream could not be closed.");
            }

            task = states.advance(command, task, KnowledgeFileStatus.PARSING,
                    KnowledgeFileStatus.CHUNKING, "CHUNKING", 30, taskLease);
            List<ChunkDraft> drafts = chunker.split(document, kb.chunkPolicy());
            if (drafts.isEmpty()) {
                throw new BusinessException(ErrorCode.EMPTY_DOCUMENT,
                        "The document contains no extractable text.");
            }
            chunkWriter.replaceChunks(command.tenantId(), kb.id(), command.fileId(), drafts);

            task = states.advance(command, task, KnowledgeFileStatus.CHUNKING,
                    KnowledgeFileStatus.EMBEDDING, "EMBEDDING", 55, taskLease);
            List<KnowledgeChunk> persisted = chunks.findByFile(command.tenantId(), kb.id(), command.fileId());
            EmbeddingResult embedded = embeddings.embed(new EmbeddingRequest(
                    command.tenantId(), kb.embeddingProviderId(), kb.embeddingModel(), null,
                    persisted.stream().map(KnowledgeChunk::content).toList()));
            if (embedded.vectors().size() != persisted.size()) {
                throw new BusinessException(ErrorCode.MODEL_BAD_RESPONSE,
                        "The embedding provider returned an unexpected number of vectors.");
            }

            String modelSpec = kb.embeddingModel();
            task = states.beginIndexing(command, task, persisted.size(), modelSpec, taskLease);
            // Compensation before upsert removes stale vector identities from an older
            // parse. Both operations are file-scoped and idempotent.
            vectors.deleteByFile(command.tenantId().value(), kb.id(), command.fileId());
            vectors.upsert(toVectors(persisted, embedded.vectors(), modelSpec));
            states.complete(command, task, persisted.size(), modelSpec);
            return KnowledgeFileIngestionOutcome.COMPLETED;
        } catch (RuntimeException failure) {
            return states.fail(command, task, IngestionFailure.from(failure));
        }
    }

    private static void requireEmbeddingBinding(KnowledgeBase kb) {
        if (kb.embeddingProviderId() == null || kb.embeddingModel() == null) {
            throw new BusinessException(ErrorCode.MODEL_CONFIGURATION_ERROR,
                    "The knowledge base has no embedding model configured.");
        }
    }

    private static List<VectorChunk> toVectors(List<KnowledgeChunk> chunks,
                                               List<float[]> embeddings,
                                               String modelSpec) {
        List<VectorChunk> result = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            KnowledgeChunk chunk = chunks.get(index);
            result.add(new VectorChunk(chunk.tenantId(), chunk.knowledgeBaseId(), chunk.fileId(),
                    chunk.id(), chunk.content(), embeddings.get(index), modelSpec));
        }
        return List.copyOf(result);
    }
}
