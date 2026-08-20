package com.knowagent.knowledge.file;

/**
 * Knowledge-file ingestion lifecycle, matching the {@code knowledge_files.status}
 * CHECK values exactly. Legal transitions are centralized here so the application
 * services, the persistence layer and (later) the worker never drift.
 *
 * <p>This task only produces {@code UPLOADED -> QUEUED}: the upload transaction writes
 * the file already enqueued, so only the terminal {@code QUEUED} row is persisted. The
 * remaining edges are the contract the ingestion worker and delete flows will follow.
 */
public enum KnowledgeFileStatus {

    UPLOADED,
    QUEUED,
    PARSING,
    CHUNKING,
    EMBEDDING,
    INDEXING,
    READY,
    FAILED,
    DELETING,
    DELETED;

    /** Whether a transition to {@code target} is legal under the centralized state machine. */
    public boolean canTransitionTo(KnowledgeFileStatus target) {
        return switch (this) {
            case UPLOADED -> target == QUEUED || target == FAILED;
            case QUEUED -> target == PARSING || target == FAILED;
            case PARSING -> target == CHUNKING || target == FAILED;
            case CHUNKING -> target == EMBEDDING || target == FAILED;
            case EMBEDDING -> target == INDEXING || target == FAILED;
            case INDEXING -> target == READY || target == FAILED;
            // A retry re-enqueues a failed file; a delete starts from a settled state.
            case FAILED -> target == QUEUED || target == DELETING;
            case READY -> target == DELETING;
            case DELETING -> target == DELETED;
            // Terminal; a soft-deleted row is unreachable through normal queries.
            case DELETED -> false;
        };
    }
}
