package com.knowagent.security.application.service;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;

/**
 * The submitted Refresh Token is not usable: unknown, expired, revoked, consumed
 * (a replay) or otherwise rejected.
 *
 * <p>Extends {@link BusinessException} so the web layer maps it to the stable JSON
 * 401 {@link ErrorCode#INVALID_CREDENTIALS}. The message never reveals which token
 * failed or what else belongs to the same family.
 *
 * <p>Rotation and the API credential-refresh facade mark this exception as
 * {@code noRollbackFor}: the replay revocation committed inside the transaction
 * must survive the rejection, while a genuine infrastructure or Access Token
 * signing failure still rolls the consume and child insert back together.
 */
public final class RefreshTokenInvalidException extends BusinessException {

    public RefreshTokenInvalidException() {
        super(ErrorCode.INVALID_CREDENTIALS, "Invalid or expired refresh token.");
    }
}
