package com.knowagent.worker.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.knowledge.application.service.KnowledgeFileSubmissionService;
import com.knowagent.observability.outbox.OutboxEvent;
import com.knowagent.observability.outbox.OutboxStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestionEventCodecTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final IngestionEventCodec codec = new IngestionEventCodec(mapper);

    @Test
    void publishesOnlyTheVersionedAllowlistedEnvelope() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-08-18T00:00:00Z");
        OutboxEvent event = new OutboxEvent(eventId, TenantId.of(tenantId),
                KnowledgeFileSubmissionService.AGGREGATE_TYPE, fileId.toString(),
                KnowledgeFileSubmissionService.EVENT_TYPE,
                JsonNodeFactory.instance.objectNode().put("file_id", fileId.toString()),
                JsonNodeFactory.instance.objectNode().put("authorization", "must-not-leak"),
                OutboxStatus.PENDING, 0, 5, createdAt, null, null, null, null, 0, createdAt);

        var json = mapper.readTree(codec.encode(event));

        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "eventId", "eventType", "tenantId", "aggregateId",
                "occurredAt", "schemaVersion", "payload");
        assertThat(json.toString()).doesNotContain("authorization", "must-not-leak", "objectKey");
        IngestionEventEnvelope decoded = codec.decode(json.toString());
        assertThat(decoded.eventId()).isEqualTo(eventId);
        assertThat(decoded.tenantId()).isEqualTo(TenantId.of(tenantId));
        assertThat(decoded.fileId()).isEqualTo(fileId);
        assertThat(decoded.payloadHash()).matches("^[0-9a-f]{64}$");
    }

    @Test
    void rejectsUnknownSchemaTypeTenantAndObjectPathBeforeDispatch() {
        UUID eventId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        String valid = """
                {"eventId":"%s","eventType":"%s","tenantId":"%s","aggregateId":"%s",
                 "occurredAt":"2026-08-18T00:00:00Z","schemaVersion":1,
                 "payload":{"file_id":"%s"}}
                """.formatted(eventId, KnowledgeFileSubmissionService.EVENT_TYPE,
                tenantId, fileId, fileId);

        assertThatThrownBy(() -> codec.decode(valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2")))
                .isInstanceOf(InvalidEventEnvelopeException.class);
        assertThatThrownBy(() -> codec.decode(valid.replace(KnowledgeFileSubmissionService.EVENT_TYPE, "unknown")))
                .isInstanceOf(InvalidEventEnvelopeException.class);
        assertThatThrownBy(() -> codec.decode(valid.replace(tenantId.toString(), "not-a-tenant")))
                .isInstanceOf(InvalidEventEnvelopeException.class);
        assertThatThrownBy(() -> codec.decode(valid.replace(
                "\"file_id\":\"" + fileId + "\"",
                "\"file_id\":\"" + fileId + "\",\"object_key\":\"tenant/private.pdf\"")))
                .isInstanceOf(InvalidEventEnvelopeException.class);
        assertThatThrownBy(() -> codec.decode(valid.substring(0, valid.length() - 2)
                + ",\"secret\":\"x\"}"))
                .isInstanceOf(InvalidEventEnvelopeException.class);
    }
}
