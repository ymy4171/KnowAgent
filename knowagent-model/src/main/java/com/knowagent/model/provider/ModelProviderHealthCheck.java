package com.knowagent.model.provider;

import java.util.Objects;
import java.util.UUID;

/**
 * Result of a provider health check. Until a real adapter probe is wired, {@code checked}
 * is always {@code false} and {@code status} stays {@link HealthStatus#UNKNOWN} - a
 * fabricated {@code HEALTHY} is never reported.
 */
public record ModelProviderHealthCheck(UUID providerId, HealthStatus status, boolean checked, String message) {

    public ModelProviderHealthCheck {
        Objects.requireNonNull(providerId, "providerId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }
}
