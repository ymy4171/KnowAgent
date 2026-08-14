package com.knowagent.security.application.service;

/**
 * Inbound port for the login flow.
 *
 * <p>The service authenticates a local user against its tenant, loads effective
 * roles and permissions, records login state, and issues a hashed Refresh Token.
 * Signing the Access Token is deliberately left to the web module
 * ({@code knowagent-api}), which owns the JWT infrastructure; this service returns
 * everything the caller needs to sign one.
 */
public interface Login {

    LoginResult login(LoginCommand command);
}
