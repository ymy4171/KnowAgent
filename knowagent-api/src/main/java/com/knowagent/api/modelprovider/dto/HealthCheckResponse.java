package com.knowagent.api.modelprovider.dto;

import com.knowagent.model.provider.HealthStatus;
import com.knowagent.model.provider.ModelProviderHealthCheck;

import java.util.UUID;

/** Health-check result. {@code checked} is {@code false} until a real adapter probe is wired. */
public record HealthCheckResponse(UUID providerId, HealthStatus status, boolean checked, String message) {

    public static HealthCheckResponse from(ModelProviderHealthCheck check) {
        return new HealthCheckResponse(check.providerId(), check.status(), check.checked(), check.message());
    }
}
