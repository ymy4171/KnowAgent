package com.knowagent.knowledge.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.knowagent.common.tenant.TenantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeFileTest {

    private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
    private static final UUID KB = UUID.randomUUID();
    private static final String SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void constructsAValidQueuedFile() {
        KnowledgeFile file = base(KnowledgeFileStatus.UPLOADED);
        assertThat(file.sha256()).isEqualTo(SHA);
        assertThat(file.processingParams().isObject()).isTrue();
        assertThat(file.status()).isEqualTo(KnowledgeFileStatus.UPLOADED);
    }

    @Test
    void rejectsBlankRequiredStrings() {
        assertThatThrownBy(() -> base(b -> b.displayName("  ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base(b -> b.originalFilename("")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base(b -> b.objectKey(" ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base(b -> b.contentType("")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOverlongDisplayNameAndFilename() {
        assertThatThrownBy(() -> base(b -> b.displayName("x".repeat(513))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base(b -> b.originalFilename("x".repeat(513))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMalformedSha256NegativeCountsAndNonObjectJson() {
        assertThatThrownBy(() -> base(b -> b.sha256("not-a-hash")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base(b -> b.sha256("A".repeat(64))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base(b -> b.fileSizeBytes(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base(b -> b.chunkCount(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base(b -> b.tokenCount(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base(b -> b.processingParams(JsonNodeFactory.instance.arrayNode())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base(b -> b.metadata(JsonNodeFactory.instance.textNode("x"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base(b -> b.version(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void copiesJsonSoTheAggregateCannotBeMutatedFromOutside() {
        ObjectNode processing = JsonNodeFactory.instance.objectNode().put("task_id", "abc");
        KnowledgeFile file = base(b -> b.processingParams(processing));

        processing.put("task_id", "mutated");

        assertThat(file.processingParams().get("task_id").asText()).isEqualTo("abc");
    }

    @Test
    void transitionToAppliesTheCentralizedMachine() {
        KnowledgeFile uploaded = base(KnowledgeFileStatus.UPLOADED);
        KnowledgeFile queued = uploaded.transitionTo(KnowledgeFileStatus.QUEUED);

        assertThat(queued.status()).isEqualTo(KnowledgeFileStatus.QUEUED);
        assertThat(queued.id()).isEqualTo(uploaded.id());
        assertThat(queued.version()).isEqualTo(uploaded.version());
        assertThat(queued.updatedAt()).isAfterOrEqualTo(uploaded.updatedAt());
    }

    @Test
    void transitionToRejectsIllegalEdges() {
        KnowledgeFile uploaded = base(KnowledgeFileStatus.UPLOADED);
        assertThatThrownBy(() -> uploaded.transitionTo(KnowledgeFileStatus.READY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("illegal knowledge-file transition");
    }

    @Test
    void toStringOmitsStorageKeyProcessingParamsAndErrorText() {
        KnowledgeFile file = base(KnowledgeFileStatus.QUEUED);
        assertThat(file.toString())
                .contains("displayName=guide.txt", "status=QUEUED")
                .doesNotContain("objectKey", "tenants/", "processingParams", "task_id",
                        "originalFilename", "sha256");
    }

    private static KnowledgeFile base(KnowledgeFileStatus status) {
        return base(builder -> builder.status(status));
    }

    private static KnowledgeFile base(java.util.function.Consumer<Builder> customize) {
        Builder builder = new Builder();
        customize.accept(builder);
        return builder.build();
    }

    private static final class Builder {
        String displayName = "guide.txt";
        String originalFilename = "guide.txt";
        String objectKey = "tenants/" + TENANT.value() + "/knowledge-bases/" + KB + "/files/x/source";
        String contentType = "text/plain";
        String fileExtension = "txt";
        String sha256 = SHA;
        long fileSizeBytes = 42;
        KnowledgeFileStatus status = KnowledgeFileStatus.UPLOADED;
        int chunkCount;
        long tokenCount;
        JsonNode processingParams = KnowledgeFile.emptyProcessingParams();
        JsonNode metadata = KnowledgeFile.emptyMetadata();
        long version;

        Builder displayName(String v) { this.displayName = v; return this; }
        Builder originalFilename(String v) { this.originalFilename = v; return this; }
        Builder objectKey(String v) { this.objectKey = v; return this; }
        Builder contentType(String v) { this.contentType = v; return this; }
        Builder sha256(String v) { this.sha256 = v; return this; }
        Builder fileSizeBytes(long v) { this.fileSizeBytes = v; return this; }
        Builder status(KnowledgeFileStatus v) { this.status = v; return this; }
        Builder chunkCount(int v) { this.chunkCount = v; return this; }
        Builder tokenCount(long v) { this.tokenCount = v; return this; }
        Builder processingParams(JsonNode v) { this.processingParams = v; return this; }
        Builder metadata(JsonNode v) { this.metadata = v; return this; }
        Builder version(long v) { this.version = v; return this; }

        KnowledgeFile build() {
            return new KnowledgeFile(UUID.randomUUID(), TENANT, KB, null, null, displayName,
                    originalFilename, objectKey, contentType, fileExtension, sha256, fileSizeBytes,
                    status, chunkCount, tokenCount, processingParams, metadata, null, null, false,
                    null, null, version, Instant.EPOCH, Instant.EPOCH, null);
        }
    }
}
