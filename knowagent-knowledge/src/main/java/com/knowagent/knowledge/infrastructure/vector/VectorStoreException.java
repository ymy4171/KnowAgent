package com.knowagent.knowledge.infrastructure.vector;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;

/**
 * Stable failure raised by the vector-store boundary.
 *
 * <p>The adapter never leaks the underlying Milvus error body, endpoint, credentials
 * or any vector/chunk content into the message: callers map the {@link ErrorCode} to
 * a stable API error and the message stays generic. The error code is the contract,
 * so one catch site can handle every vector-store failure.
 */
public class VectorStoreException extends BusinessException {

    public VectorStoreException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
