package com.knowagent.worker.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.knowledge.application.service.KnowledgeFileSubmissionService;
import com.knowagent.observability.outbox.OutboxEvent;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

/** Strict codec that whitelists the only payload field accepted by prompt 17. */
@Component
public class IngestionEventCodec {

    public static final int SCHEMA_VERSION = 1;
    public static final String ENVELOPE_FIELD = "envelope";
    private static final Set<String> ROOT_FIELDS = Set.of(
            "eventId", "eventType", "tenantId", "aggregateId",
            "occurredAt", "schemaVersion", "payload");
    private static final Set<String> PAYLOAD_FIELDS = Set.of("file_id");

    private final ObjectMapper mapper;

    public IngestionEventCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String encode(OutboxEvent event) {
        validateEventType(event.eventType());
        UUID aggregateId = parseUuid(event.aggregateId(), "aggregateId");
        JsonNode payload = validatedPayload(event.payload(), aggregateId);
        ObjectNode root = mapper.createObjectNode();
        root.put("eventId", event.id().toString());
        root.put("eventType", event.eventType());
        root.put("tenantId", event.tenantId().value().toString());
        root.put("aggregateId", aggregateId.toString());
        root.put("occurredAt", event.createdAt().toString());
        root.put("schemaVersion", SCHEMA_VERSION);
        root.set("payload", payload);
        try {
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException failure) {
            throw new InvalidEventEnvelopeException("The event envelope could not be encoded.");
        }
    }

    /** Validates schema/type/tenant/payload before any TenantContext is installed. */
    public IngestionEventEnvelope decode(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.isObject()) {
                throw invalid();
            }
            requireOnlyFields(root, ROOT_FIELDS);
            int schemaVersion = requiredInt(root, "schemaVersion");
            if (schemaVersion != SCHEMA_VERSION) {
                throw new InvalidEventEnvelopeException("Unsupported event schema version.");
            }
            String eventType = requiredText(root, "eventType");
            validateEventType(eventType);
            UUID eventId = parseUuid(requiredText(root, "eventId"), "eventId");
            TenantId tenantId = new TenantId(parseUuid(requiredText(root, "tenantId"), "tenantId"));
            UUID aggregateId = parseUuid(requiredText(root, "aggregateId"), "aggregateId");
            Instant occurredAt = Instant.parse(requiredText(root, "occurredAt"));
            JsonNode payload = validatedPayload(root.get("payload"), aggregateId);
            return new IngestionEventEnvelope(eventId, eventType, tenantId, aggregateId,
                    occurredAt, schemaVersion, payload, sha256(payload));
        } catch (InvalidEventEnvelopeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw invalid();
        }
    }

    private JsonNode validatedPayload(JsonNode payload, UUID aggregateId) {
        if (payload == null || !payload.isObject()) {
            throw invalid();
        }
        requireOnlyFields(payload, PAYLOAD_FIELDS);
        UUID fileId = parseUuid(requiredText(payload, "file_id"), "file_id");
        if (!aggregateId.equals(fileId)) {
            throw new InvalidEventEnvelopeException("The event aggregate does not match its payload.");
        }
        return payload.deepCopy();
    }

    private static void requireOnlyFields(JsonNode object, Set<String> allowed) {
        Iterator<String> names = object.fieldNames();
        while (names.hasNext()) {
            if (!allowed.contains(names.next())) {
                throw new InvalidEventEnvelopeException("The event envelope contains an unsupported field.");
            }
        }
    }

    private static void validateEventType(String eventType) {
        if (!KnowledgeFileSubmissionService.EVENT_TYPE.equals(eventType)) {
            throw new InvalidEventEnvelopeException("Unsupported event type.");
        }
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw invalid();
        }
        return value.asText();
    }

    private static int requiredInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToInt()) {
            throw invalid();
        }
        return value.intValue();
    }

    private static UUID parseUuid(String value, String ignoredField) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    private String sha256(JsonNode payload) {
        try {
            byte[] canonical = mapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JsonProcessingException | NoSuchAlgorithmException failure) {
            throw new InvalidEventEnvelopeException("The event payload could not be hashed.");
        }
    }

    private static InvalidEventEnvelopeException invalid() {
        return new InvalidEventEnvelopeException("The event envelope is invalid.");
    }
}
