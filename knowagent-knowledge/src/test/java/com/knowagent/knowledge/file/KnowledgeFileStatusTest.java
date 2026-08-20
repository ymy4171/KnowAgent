package com.knowagent.knowledge.file;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the centralized knowledge-file state machine so the application services, the
 * persistence layer and the future worker never drift. The edges match the
 * {@code knowledge_files.status} CHECK exactly.
 */
class KnowledgeFileStatusTest {

    @Test
    void uploadEdgesAreOnlyQueueOrFail() {
        assertThat(KnowledgeFileStatus.UPLOADED.canTransitionTo(KnowledgeFileStatus.QUEUED)).isTrue();
        assertThat(KnowledgeFileStatus.UPLOADED.canTransitionTo(KnowledgeFileStatus.FAILED)).isTrue();
        assertThat(KnowledgeFileStatus.UPLOADED.canTransitionTo(KnowledgeFileStatus.PARSING)).isFalse();
        assertThat(KnowledgeFileStatus.UPLOADED.canTransitionTo(KnowledgeFileStatus.READY)).isFalse();
        assertThat(KnowledgeFileStatus.UPLOADED.canTransitionTo(KnowledgeFileStatus.UPLOADED)).isFalse();
    }

    @Test
    void ingestionAdvancesForwardOnlyWithFailureAsTheOnlySidestep() {
        assertThat(KnowledgeFileStatus.QUEUED.canTransitionTo(KnowledgeFileStatus.PARSING)).isTrue();
        assertThat(KnowledgeFileStatus.QUEUED.canTransitionTo(KnowledgeFileStatus.FAILED)).isTrue();
        assertThat(KnowledgeFileStatus.PARSING.canTransitionTo(KnowledgeFileStatus.CHUNKING)).isTrue();
        assertThat(KnowledgeFileStatus.PARSING.canTransitionTo(KnowledgeFileStatus.FAILED)).isTrue();
        assertThat(KnowledgeFileStatus.CHUNKING.canTransitionTo(KnowledgeFileStatus.EMBEDDING)).isTrue();
        assertThat(KnowledgeFileStatus.CHUNKING.canTransitionTo(KnowledgeFileStatus.FAILED)).isTrue();
        assertThat(KnowledgeFileStatus.EMBEDDING.canTransitionTo(KnowledgeFileStatus.INDEXING)).isTrue();
        assertThat(KnowledgeFileStatus.EMBEDDING.canTransitionTo(KnowledgeFileStatus.FAILED)).isTrue();
        assertThat(KnowledgeFileStatus.INDEXING.canTransitionTo(KnowledgeFileStatus.READY)).isTrue();
        assertThat(KnowledgeFileStatus.INDEXING.canTransitionTo(KnowledgeFileStatus.FAILED)).isTrue();

        assertThat(KnowledgeFileStatus.QUEUED.canTransitionTo(KnowledgeFileStatus.READY)).isFalse();
        assertThat(KnowledgeFileStatus.PARSING.canTransitionTo(KnowledgeFileStatus.READY)).isFalse();
        assertThat(KnowledgeFileStatus.EMBEDDING.canTransitionTo(KnowledgeFileStatus.QUEUED)).isFalse();
    }

    @Test
    void failuresRetryFromQueueAndDeleteStartsFromASettledState() {
        assertThat(KnowledgeFileStatus.FAILED.canTransitionTo(KnowledgeFileStatus.QUEUED)).isTrue();
        assertThat(KnowledgeFileStatus.FAILED.canTransitionTo(KnowledgeFileStatus.DELETING)).isTrue();
        assertThat(KnowledgeFileStatus.FAILED.canTransitionTo(KnowledgeFileStatus.PARSING)).isFalse();
        assertThat(KnowledgeFileStatus.READY.canTransitionTo(KnowledgeFileStatus.DELETING)).isTrue();
        assertThat(KnowledgeFileStatus.READY.canTransitionTo(KnowledgeFileStatus.QUEUED)).isFalse();
        assertThat(KnowledgeFileStatus.DELETING.canTransitionTo(KnowledgeFileStatus.DELETED)).isTrue();
        // Terminal: DELETED cannot transition anywhere, not even to itself.
        assertThat(KnowledgeFileStatus.DELETED.canTransitionTo(KnowledgeFileStatus.DELETED)).isFalse();
        assertThat(KnowledgeFileStatus.DELETED.canTransitionTo(KnowledgeFileStatus.QUEUED)).isFalse();
    }
}
