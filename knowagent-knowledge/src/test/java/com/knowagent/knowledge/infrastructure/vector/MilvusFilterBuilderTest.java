package com.knowagent.knowledge.infrastructure.vector;

import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Locks the controlled Milvus filter construction: the tenant and knowledge-base
 * clauses are always present, optional file ids are validated and escaped, and
 * arbitrary user expressions can never be concatenated (Rule 9).
 */
class MilvusFilterBuilderTest {

    private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
    private static final UUID KB = UUID.randomUUID();

    @Test
    void baseFilterAlwaysScopesTenantAndKnowledgeBase() {
        assertThat(MilvusFilterBuilder.forTenantAndKnowledgeBase(TENANT, KB))
                .isEqualTo("tenant_id == '" + TENANT.value() + "' && knowledge_base_id == '" + KB + "'");
    }

    @Test
    void nullTenantOrKnowledgeBaseIsRejected() {
        assertThatThrownBy(() -> MilvusFilterBuilder.forTenantAndKnowledgeBase(null, KB))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> MilvusFilterBuilder.forTenantAndKnowledgeBase(TENANT, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullOrEmptyFileIdsProduceTheBaseFilter() {
        assertThat(MilvusFilterBuilder.withFileIds(TENANT, KB, null))
                .isEqualTo(MilvusFilterBuilder.forTenantAndKnowledgeBase(TENANT, KB));
        assertThat(MilvusFilterBuilder.withFileIds(TENANT, KB, List.of()))
                .isEqualTo(MilvusFilterBuilder.forTenantAndKnowledgeBase(TENANT, KB));
    }

    @Test
    void singleFileIdUsesAnInClauseWithEscapedLiteral() {
        UUID file = UUID.randomUUID();
        assertThat(MilvusFilterBuilder.withFileIds(TENANT, KB, List.of(file)))
                .isEqualTo("tenant_id == '" + TENANT.value() + "' && knowledge_base_id == '" + KB
                        + "' && file_id in ['" + file + "']");
    }

    @Test
    void multipleFileIdsKeepTheirOrder() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        String filter = MilvusFilterBuilder.withFileIds(TENANT, KB, List.of(first, second, third));
        assertThat(filter).endsWith("file_id in ['" + first + "','" + second + "','" + third + "']");
        assertThat(filter.indexOf(first.toString())).isLessThan(filter.indexOf(second.toString()));
        assertThat(filter.indexOf(second.toString())).isLessThan(filter.indexOf(third.toString()));
    }

    @Test
    void aNullFileIdEntryFailsClosedWithValidationError() {
        List<UUID> fileIds = new java.util.ArrayList<>();
        fileIds.add(UUID.randomUUID());
        fileIds.add(null);
        assertThatThrownBy(() -> MilvusFilterBuilder.withFileIds(TENANT, KB, fileIds))
                .isInstanceOf(VectorStoreException.class)
                .satisfies(e -> assertThat(((VectorStoreException) e).errorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void escapingQuotesBackslashesAndNeverAllowsRawExpressions() {
        assertThat(MilvusFilterBuilder.escape("simple")).isEqualTo("simple");
        assertThat(MilvusFilterBuilder.escape("a'b")).isEqualTo("a\\'b");
        assertThat(MilvusFilterBuilder.escape("a\\b")).isEqualTo("a\\\\b");
        assertThat(MilvusFilterBuilder.escape("'\\'")).isEqualTo("\\'\\\\\\'");

        // Even if an attacker-shaped string reached the escape function, the result
        // is a quoted literal: every inner quote is escaped, so it cannot break out
        // of the single-quoted expression (the || stays inert text inside quotes).
        String hostile = "x' || 1 == 1 || '";
        String quoted = "'" + MilvusFilterBuilder.escape(hostile) + "'";
        assertThat(quoted).isEqualTo("'x\\' || 1 == 1 || \\''");
    }
}
