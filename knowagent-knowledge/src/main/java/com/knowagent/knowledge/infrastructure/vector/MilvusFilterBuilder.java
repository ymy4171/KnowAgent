package com.knowagent.knowledge.infrastructure.vector;

import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The only place that builds Milvus boolean-expression filters. It always starts
 * from {@code tenant_id} and {@code knowledge_base_id} (Rule 7: every Milvus
 * write/search/delete is scoped by both), optionally adds a {@code file_id in [...]}
 * clause and escapes every string literal. Arbitrary user expressions are never
 * concatenated (Rule 9): all values are typed UUIDs produced by the caller.
 *
 * <p>Escaping: a literal is enclosed in single quotes; {@code \} becomes {@code \\}
 * and {@code '} becomes {@code \'}, matching Milvus's boolean-expression grammar.
 */
public final class MilvusFilterBuilder {

    private MilvusFilterBuilder() {
    }

    /** {@code tenant_id == '...' && knowledge_base_id == '...'} */
    public static String forTenantAndKnowledgeBase(TenantId tenantId, UUID knowledgeBaseId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        return "tenant_id == " + quoted(tenantId.value())
                + " && knowledge_base_id == " + quoted(knowledgeBaseId);
    }

    /**
     * Adds an optional {@code file_id in [...]} clause. An empty or {@code null} list
     * produces the base filter; every element must be non-null (a null element is a
     * caller bug and fails closed with VALIDATION_ERROR rather than a broken filter).
     * Milvus 2.5 requires the right-hand side of {@code in} to be a bracket list.
     */
    public static String withFileIds(TenantId tenantId, UUID knowledgeBaseId, List<UUID> fileIds) {
        String base = forTenantAndKnowledgeBase(tenantId, knowledgeBaseId);
        if (fileIds == null || fileIds.isEmpty()) {
            return base;
        }
        StringBuilder expression = new StringBuilder(base);
        expression.append(" && file_id in [");
        boolean first = true;
        for (UUID fileId : fileIds) {
            if (fileId == null) {
                throw new VectorStoreException(ErrorCode.VALIDATION_ERROR,
                        "The file id filter contains a null entry.");
            }
            if (!first) {
                expression.append(',');
            }
            expression.append(quoted(fileId));
            first = false;
        }
        return expression.append(']').toString();
    }

    private static String quoted(UUID value) {
        return "'" + escape(value.toString()) + "'";
    }

    /** Escapes a string literal for use inside single quotes in a Milvus filter. */
    static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '\'') {
                escaped.append('\\');
            }
            escaped.append(c);
        }
        return escaped.toString();
    }
}
