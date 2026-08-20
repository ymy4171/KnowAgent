package com.knowagent.knowledge.knowledgebase;

/**
 * Knowledge-base lifecycle, matching the {@code knowledge_bases.status} CHECK values
 * exactly. Legal transitions are centralized here so the application service and the
 * persistence layer never drift.
 *
 * <ul>
 *   <li>{@code ACTIVE} {@code <->} {@code DISABLED}: a knowledge base can be disabled
 *       and re-enabled by PATCH.</li>
 *   <li>{@code -> DELETED}: only the dedicated DELETE endpoint performs the soft
 *       delete (after verifying there are no active files); PATCH cannot set it.</li>
 *   <li>{@code DELETING} is reserved for the future cascade-delete flow (提示词十九)
 *       and is not produced by this task.</li>
 *   <li>{@code DELETED} is terminal; the row is soft-deleted and invisible to all
 *       queries.</li>
 * </ul>
 */
public enum KnowledgeBaseStatus {

    ACTIVE,
    DISABLED,
    DELETING,
    DELETED;

    /**
     * Whether a transition to {@code target} is legal. {@code DELETED} is deliberately
     * not a PATCH target and is only reached through the delete flow.
     */
    public boolean canTransitionTo(KnowledgeBaseStatus target) {
        return switch (this) {
            case ACTIVE -> target == DISABLED;
            case DISABLED -> target == ACTIVE;
            // Only the future cascade-delete worker may move a row out of DELETING.
            case DELETING -> false;
            // Terminal; soft-deleted rows are unreachable through normal queries.
            case DELETED -> false;
        };
    }
}
