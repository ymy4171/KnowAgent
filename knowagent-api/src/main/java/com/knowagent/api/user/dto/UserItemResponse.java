package com.knowagent.api.user.dto;

import com.knowagent.security.domain.user.UserStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * HTTP response body for one user in {@code GET /api/v1/users} and
 * {@code GET /api/v1/users/{userId}}.
 *
 * <p>A pure HTTP DTO built by the controller from the domain {@code User}. The
 * record has only business-visible fields, so internal state - password hash,
 * failed-login count, lock window, timestamps - structurally cannot appear in the
 * response.
 */
public record UserItemResponse(
        UUID userId,
        UUID departmentId,
        String loginName,
        String displayName,
        String email,
        String phoneNumber,
        UserStatus status,
        Instant createdAt) {

    public UserItemResponse {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(loginName, "loginName must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
