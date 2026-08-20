package com.knowagent.model.provider;

/**
 * A capability a model provider declares, stored as the JSONB string array
 * {@code capabilities}. A provider can serve more than one capability (for example
 * chat and embedding on the same OpenAI-compatible endpoint).
 */
public enum ModelCapability {
    CHAT,
    EMBEDDING,
    RERANK
}
