package com.knowagent.security.application.service;

import java.util.Objects;

/**
 * A logout request: the raw one-time refresh token identifying the family to
 * revoke.
 *
 * <p>The raw token is redacted from {@link #toString()} and only its SHA-256 hash
 * reaches the database, exactly as in {@link RefreshCommand}.
 */
public record LogoutCommand(String refreshToken) {

    public LogoutCommand {
        Objects.requireNonNull(refreshToken, "refreshToken must not be null");
    }

    @Override
    public String toString() {
        return "LogoutCommand[refreshToken=[REDACTED]]";
    }
}
