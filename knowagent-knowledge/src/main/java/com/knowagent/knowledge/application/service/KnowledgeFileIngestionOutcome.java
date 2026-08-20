package com.knowagent.knowledge.application.service;

/** Determines whether the Redis record can be acknowledged. */
public enum KnowledgeFileIngestionOutcome {
    COMPLETED(true),
    TERMINAL_FAILURE(true),
    ALREADY_PROCESSED(true),
    DEFERRED(false);

    private final boolean acknowledge;

    KnowledgeFileIngestionOutcome(boolean acknowledge) {
        this.acknowledge = acknowledge;
    }

    public boolean acknowledge() {
        return acknowledge;
    }
}
