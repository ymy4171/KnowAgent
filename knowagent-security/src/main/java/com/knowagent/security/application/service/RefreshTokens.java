package com.knowagent.security.application.service;

/**
 * Inbound port for the refresh-token lifecycle: single-use rotation and logout.
 *
 * <p>{@link #refresh} consumes the presented token and issues a successor in the
 * same family (an ACTIVE token is marked CONSUMED and a child is inserted), then
 * returns everything the web layer needs to sign a fresh Access Token - the same
 * contract a successful login returns, so the same {@link LoginResult} is reused.
 * A consumed token reappearing is a replay: the whole family is revoked and a
 * stable {@link RefreshTokenInvalidException} is thrown. {@link #logout} revokes
 * the family a token belongs to and is idempotent.
 */
public interface RefreshTokens {

    LoginResult refresh(RefreshCommand command);

    void logout(LogoutCommand command);
}
