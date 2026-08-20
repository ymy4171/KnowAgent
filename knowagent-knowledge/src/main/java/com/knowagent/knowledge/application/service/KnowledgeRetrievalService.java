package com.knowagent.knowledge.application.service;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.knowledge.application.port.out.KnowledgeBaseRepository;
import com.knowagent.knowledge.application.port.out.KnowledgeFileRepository;
import com.knowagent.knowledge.application.port.out.KnowledgeRetrievalObserver;
import com.knowagent.knowledge.application.port.out.KnowledgeRetrievalRepository;
import com.knowagent.knowledge.application.port.out.RetrievalChunkRecord;
import com.knowagent.knowledge.chunk.ChunkIndexStatus;
import com.knowagent.knowledge.file.KnowledgeFile;
import com.knowagent.knowledge.file.KnowledgeFileStatus;
import com.knowagent.knowledge.knowledgebase.KnowledgeBase;
import com.knowagent.knowledge.knowledgebase.KnowledgeBaseStatus;
import com.knowagent.knowledge.knowledgebase.RetrievalConfig;
import com.knowagent.knowledge.vector.VectorHit;
import com.knowagent.knowledge.vector.VectorQuery;
import com.knowagent.knowledge.vector.VectorStoreGateway;
import com.knowagent.model.embedding.EmbeddingGateway;
import com.knowagent.model.embedding.EmbeddingRequest;
import com.knowagent.model.embedding.EmbeddingResult;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Semantic retrieval orchestration. PostgreSQL validation is deliberately split
 * around the external embedding and Milvus calls: no database transaction is held
 * while calling either service, and the final PostgreSQL hydration is authoritative
 * for every returned citation.
 */
@Service
public class KnowledgeRetrievalService {

    static final int MAX_QUERY_CHARACTERS = 10_000;
    static final int MAX_FILE_FILTERS = 100;

    private final KnowledgeBaseRepository knowledgeBases;
    private final KnowledgeFileRepository files;
    private final KnowledgeRetrievalRepository retrievalRepository;
    private final EmbeddingGateway embeddingGateway;
    private final VectorStoreGateway vectorStoreGateway;
    private final KnowledgeRetrievalObserver observer;

    public KnowledgeRetrievalService(KnowledgeBaseRepository knowledgeBases,
                                     KnowledgeFileRepository files,
                                     KnowledgeRetrievalRepository retrievalRepository,
                                     EmbeddingGateway embeddingGateway,
                                     VectorStoreGateway vectorStoreGateway,
                                     KnowledgeRetrievalObserver observer) {
        this.knowledgeBases = Objects.requireNonNull(knowledgeBases, "knowledgeBases must not be null");
        this.files = Objects.requireNonNull(files, "files must not be null");
        this.retrievalRepository = Objects.requireNonNull(retrievalRepository,
                "retrievalRepository must not be null");
        this.embeddingGateway = Objects.requireNonNull(embeddingGateway, "embeddingGateway must not be null");
        this.vectorStoreGateway = Objects.requireNonNull(vectorStoreGateway,
                "vectorStoreGateway must not be null");
        this.observer = Objects.requireNonNull(observer, "observer must not be null");
    }

    public KnowledgeRetrievalResult retrieve(KnowledgeRetrievalCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        long started = System.nanoTime();
        UUID providerId = null;
        int candidateCount = 0;
        int resultCount = 0;
        String outcome = "success";
        try {
            String query = requireQuery(command.query());
            KnowledgeBase knowledgeBase = requireActiveKnowledgeBase(command.tenantId(), command.knowledgeBaseId());
            providerId = requireEmbeddingConfiguration(knowledgeBase);
            rejectUnavailableRerank(knowledgeBase);

            int topK = resolveTopK(command.topK(), knowledgeBase.retrievalConfig());
            double threshold = resolveThreshold(command.scoreThreshold(), knowledgeBase.retrievalConfig());
            List<UUID> fileIds = validateAndNormalizeFileIds(command.tenantId(), command.knowledgeBaseId(),
                    command.fileIds());

            EmbeddingResult embedding = embeddingGateway.embed(new EmbeddingRequest(
                    command.tenantId(), providerId, knowledgeBase.embeddingModel(), null, List.of(query)));
            float[] queryVector = requireSingleVector(embedding);

            // Thresholding is repeated in this application layer. Passing -1 prevents
            // an adapter-side threshold from becoming the only enforcement point.
            List<VectorHit> vectorHits = vectorStoreGateway.search(new VectorQuery(
                    command.tenantId(), command.knowledgeBaseId(), queryVector, topK, -1.0, fileIds));
            if (vectorHits == null) {
                throw new BusinessException(ErrorCode.VECTOR_BAD_RESPONSE,
                        "The vector store returned an invalid search response.");
            }

            LinkedHashMap<UUID, VectorHit> rankedUniqueHits = uniqueHits(vectorHits);
            candidateCount = rankedUniqueHits.size();
            if (rankedUniqueHits.isEmpty()) {
                return new KnowledgeRetrievalResult(command.knowledgeBaseId(), List.of());
            }

            List<RetrievalChunkRecord> rows = retrievalRepository.findByChunkIds(
                    command.tenantId(), command.knowledgeBaseId(), List.copyOf(rankedUniqueHits.keySet()));
            Map<UUID, RetrievalChunkRecord> rowsById = new LinkedHashMap<>();
            for (RetrievalChunkRecord row : rows) {
                if (row != null) {
                    rowsById.putIfAbsent(row.chunkId(), row);
                }
            }

            Set<UUID> allowedFiles = fileIds == null ? null : Set.copyOf(fileIds);
            List<KnowledgeCitation> citations = new ArrayList<>(topK);
            for (VectorHit hit : rankedUniqueHits.values()) {
                RetrievalChunkRecord row = rowsById.get(hit.chunkId());
                if (!isAuthoritativeReadyRow(row, command.tenantId(), command.knowledgeBaseId(), allowedFiles)) {
                    continue;
                }
                if (!Double.isFinite(hit.score())) {
                    throw new BusinessException(ErrorCode.VECTOR_BAD_RESPONSE,
                            "The vector store returned an invalid search score.");
                }
                if (hit.score() < threshold) {
                    continue;
                }
                citations.add(new KnowledgeCitation(row.chunkId(), row.fileId(), row.displayName(),
                        row.content(), row.pageNumber(), row.sectionPath(), hit.score(), citations.size() + 1));
                if (citations.size() == topK) {
                    break;
                }
            }
            resultCount = citations.size();
            return new KnowledgeRetrievalResult(command.knowledgeBaseId(), citations);
        } catch (BusinessException exception) {
            outcome = exception.errorCode().name().toLowerCase(java.util.Locale.ROOT);
            throw exception;
        } catch (RuntimeException exception) {
            outcome = "failure";
            throw exception;
        } finally {
            recordObservation(command.tenantId(), providerId, outcome, candidateCount, resultCount,
                    Duration.ofNanos(System.nanoTime() - started));
        }
    }

    private void recordObservation(TenantId tenantId, UUID providerId, String outcome,
            int candidateCount, int resultCount, Duration duration) {
        try {
            observer.record(tenantId, providerId, outcome, candidateCount, resultCount, duration);
        } catch (RuntimeException ignored) {
            // Telemetry is deliberately best-effort and must never change retrieval semantics.
        }
    }

    private KnowledgeBase requireActiveKnowledgeBase(TenantId tenantId, UUID knowledgeBaseId) {
        KnowledgeBase knowledgeBase = knowledgeBases.findById(tenantId, knowledgeBaseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "The knowledge base does not exist."));
        if (knowledgeBase.status() != KnowledgeBaseStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "The knowledge base is not active.");
        }
        return knowledgeBase;
    }

    private static UUID requireEmbeddingConfiguration(KnowledgeBase knowledgeBase) {
        if (knowledgeBase.embeddingProviderId() == null
                || knowledgeBase.embeddingModel() == null
                || knowledgeBase.embeddingModel().isBlank()) {
            throw new BusinessException(ErrorCode.MODEL_CONFIGURATION_ERROR,
                    "The knowledge base has no usable embedding model configuration.");
        }
        return knowledgeBase.embeddingProviderId();
    }

    private static void rejectUnavailableRerank(KnowledgeBase knowledgeBase) {
        if (knowledgeBase.retrievalConfig().rerankEnabled()) {
            throw new BusinessException(ErrorCode.MODEL_CONFIGURATION_ERROR,
                    "Rerank is enabled but no rerank adapter is available.");
        }
    }

    private List<UUID> validateAndNormalizeFileIds(TenantId tenantId, UUID knowledgeBaseId,
                                                   List<UUID> requestedFileIds) {
        if (requestedFileIds == null || requestedFileIds.isEmpty()) {
            return null;
        }
        if (requestedFileIds.size() > MAX_FILE_FILTERS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "At most " + MAX_FILE_FILTERS + " file ids may be requested.");
        }
        LinkedHashSet<UUID> unique = new LinkedHashSet<>();
        for (UUID fileId : requestedFileIds) {
            if (fileId == null) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "The file id filter contains an invalid entry.");
            }
            if (!unique.add(fileId)) {
                continue;
            }
            KnowledgeFile file = files.findById(tenantId, knowledgeBaseId, fileId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                            "A requested file does not exist."));
            if (file.status() != KnowledgeFileStatus.READY) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "A requested file is not ready for retrieval.");
            }
        }
        return List.copyOf(unique);
    }

    private static LinkedHashMap<UUID, VectorHit> uniqueHits(List<VectorHit> vectorHits) {
        LinkedHashMap<UUID, VectorHit> unique = new LinkedHashMap<>();
        for (VectorHit hit : vectorHits) {
            if (hit == null || hit.chunkId() == null) {
                throw new BusinessException(ErrorCode.VECTOR_BAD_RESPONSE,
                        "The vector store returned an invalid search response.");
            }
            unique.putIfAbsent(hit.chunkId(), hit);
        }
        return unique;
    }

    private static boolean isAuthoritativeReadyRow(RetrievalChunkRecord row, TenantId tenantId,
                                                    UUID knowledgeBaseId, Set<UUID> allowedFiles) {
        return row != null
                && row.tenantId().equals(tenantId)
                && row.knowledgeBaseId().equals(knowledgeBaseId)
                && row.indexStatus() == ChunkIndexStatus.READY
                && row.fileStatus() == KnowledgeFileStatus.READY
                && row.fileDeletedAt() == null
                && (allowedFiles == null || allowedFiles.contains(row.fileId()));
    }

    private static float[] requireSingleVector(EmbeddingResult embedding) {
        if (embedding == null || embedding.vectors() == null || embedding.vectors().size() != 1
                || embedding.vectors().getFirst() == null || embedding.vectors().getFirst().length == 0) {
            throw new BusinessException(ErrorCode.MODEL_BAD_RESPONSE,
                    "The embedding model returned an invalid query vector.");
        }
        return embedding.vectors().getFirst();
    }

    private static String requireQuery(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "The retrieval query must not be blank.");
        }
        String query = raw.trim();
        if (query.length() > MAX_QUERY_CHARACTERS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "The retrieval query is too long.");
        }
        return query;
    }

    private static int resolveTopK(Integer requested, RetrievalConfig config) {
        int topK = requested == null ? config.topK() : requested;
        if (topK < RetrievalConfig.MIN_TOP_K || topK > RetrievalConfig.MAX_TOP_K) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "topK must be between " + RetrievalConfig.MIN_TOP_K + " and "
                            + RetrievalConfig.MAX_TOP_K + ".");
        }
        return topK;
    }

    private static double resolveThreshold(Double requested, RetrievalConfig config) {
        double threshold = requested == null ? config.scoreThreshold() : requested;
        if (!Double.isFinite(threshold) || threshold < 0.0 || threshold > 1.0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "scoreThreshold must be within [0, 1].");
        }
        return threshold;
    }
}
