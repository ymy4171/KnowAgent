package com.knowagent.security.application.service;

import java.net.InetAddress;
import java.util.Objects;

/**
 * Login credentials plus the metadata the issued Refresh Token records.
 *
 * <p>The raw password is never logged, persisted or included in exception
 * messages: {@link #toString()} redacts it. {@code issuedIp} is nullable (the web
 * layer may not be able to resolve a remote address) and maps to the nullable
 * {@code issued_ip} column.
 */
public record LoginCommand(
        String tenantSlug,
        String loginName,
        String password,
        InetAddress issuedIp,
        String userAgent) {

    public LoginCommand {
        Objects.requireNonNull(tenantSlug, "tenantSlug must not be null");
        Objects.requireNonNull(loginName, "loginName must not be null");
        Objects.requireNonNull(password, "password must not be null");
    }

    @Override
    public String toString() {
        return "LoginCommand[tenantSlug=" + tenantSlug
                + ", loginName=" + loginName
                + ", password=[REDACTED]"
                + ", issuedIp=" + issuedIp
                + ", userAgent=" + userAgent + "]";
    }
}
