package com.knowagent.security.application.service;

import java.net.InetAddress;
import java.util.Objects;

/**
 * A refresh-token rotation request: the raw one-time token plus the metadata the
 * successor token records.
 *
 * <p>The raw token is never logged, persisted or included in exception messages:
 * {@link #toString()} redacts it and the database only ever sees its SHA-256 hash.
 * {@code issuedIp} is nullable (the web layer may not resolve a remote address) and
 * maps to the nullable {@code issued_ip} column.
 */
public record RefreshCommand(
        String refreshToken,
        InetAddress issuedIp,
        String userAgent) {

    public RefreshCommand {
        Objects.requireNonNull(refreshToken, "refreshToken must not be null");
    }

    @Override
    public String toString() {
        return "RefreshCommand[refreshToken=[REDACTED]"
                + ", issuedIp=" + issuedIp
                + ", userAgent=" + userAgent + "]";
    }
}
