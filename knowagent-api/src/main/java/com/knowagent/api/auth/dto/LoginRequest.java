package com.knowagent.api.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Objects;

/**
 * HTTP request body for {@code POST /api/v1/auth/login}.
 *
 * <p>A pure HTTP DTO: it never crosses into the application layer - the controller
 * maps it to a {@code LoginCommand}. Validation failures are reported as a unified
 * JSON 400 by the API exception handler. The raw password is redacted from
 * {@link #toString()} so it cannot leak through logs or exception messages.
 */
public record LoginRequest(
        @NotBlank(message = "tenantSlug must not be blank")
        @Size(max = 63, message = "tenantSlug must not exceed 63 characters")
        String tenantSlug,

        @NotBlank(message = "loginName must not be blank")
        @Size(max = 128, message = "loginName must not exceed 128 characters")
        String loginName,

        @NotBlank(message = "password must not be blank")
        @Size(max = 256, message = "password must not exceed 256 characters")
        String password) {

    public LoginRequest {
        Objects.requireNonNull(tenantSlug, "tenantSlug must not be null");
        Objects.requireNonNull(loginName, "loginName must not be null");
        Objects.requireNonNull(password, "password must not be null");
    }

    @Override
    public String toString() {
        return "LoginRequest[tenantSlug=" + tenantSlug
                + ", loginName=" + loginName
                + ", password=[REDACTED]]";
    }
}
