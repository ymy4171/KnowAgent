package com.knowagent.workspace.storage;

import java.util.Objects;

/**
 * Stable failure raised by the object-storage boundary.
 *
 * <p>The adapter never leaks the underlying MinIO/S3 error body, endpoint, bucket or
 * credentials into the message: callers map {@link Reason} to a stable API error and
 * the message stays generic. {@link Reason} is the contract, not the exception class,
 * so one catch site can handle every storage failure.
 */
public class ObjectStorageException extends RuntimeException {

    private final Reason reason;

    public ObjectStorageException(Reason reason, String message) {
        this(reason, message, null);
    }

    public ObjectStorageException(Reason reason, String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message must not be null"), cause);
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        /** The object does not exist (NoSuchKey / NoSuchObject). */
        OBJECT_NOT_FOUND,
        /** The configured credentials are rejected by the storage server. */
        ACCESS_DENIED,
        /** The storage service is unreachable or returned a transient error. */
        UNAVAILABLE,
        /** The caller violated a storage contract, e.g. an object key outside its tenant. */
        INVALID_OPERATION
    }
}
