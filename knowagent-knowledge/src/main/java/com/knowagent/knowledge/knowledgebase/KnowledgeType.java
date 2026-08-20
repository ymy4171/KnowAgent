package com.knowagent.knowledge.knowledgebase;

/**
 * Knowledge-base content source, matching the {@code knowledge_bases.knowledge_type}
 * CHECK values. {@code LOCAL} is the default; {@code EXTERNAL} is reserved for
 * externally hosted sources.
 */
public enum KnowledgeType {
    LOCAL,
    EXTERNAL
}
