package com.knowagent.security.domain.tenant;

import com.fasterxml.jackson.databind.JsonNode;
import com.knowagent.common.tenant.TenantId;

import java.time.Instant;
import java.util.Objects;

public record Tenant(
        TenantId id,
        String slug,
        String name,
        TenantStatus status,
        JsonNode settings,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
    public Tenant {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(slug, "slug must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(settings, "settings must not be null");
        if (!settings.isObject()) {
            throw new IllegalArgumentException("settings must be a JSON object");
        }
        settings = settings.deepCopy();
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }

    @Override
    public JsonNode settings() {
        return settings.deepCopy();
    }
}
