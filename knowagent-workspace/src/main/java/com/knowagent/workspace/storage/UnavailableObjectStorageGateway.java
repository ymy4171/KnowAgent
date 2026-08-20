package com.knowagent.workspace.storage;

import java.io.InputStream;

/**
 * Fail-fast {@link ObjectStorageGateway} used when no {@code minio.endpoint} is
 * configured. It exists so the application context boots without object storage (every
 * existing integration test does), while any actual storage operation fails loudly
 * with a stable {@link ObjectStorageException} instead of silently succeeding.
 */
public final class UnavailableObjectStorageGateway implements ObjectStorageGateway {

    @Override
    public StoredObject put(PutObjectCommand command) {
        throw unavailable();
    }

    @Override
    public StoredObject stat(GetObjectCommand command) {
        throw unavailable();
    }

    @Override
    public InputStream get(GetObjectCommand command) {
        throw unavailable();
    }

    @Override
    public void delete(DeleteObjectCommand command) {
        throw unavailable();
    }

    private static ObjectStorageException unavailable() {
        return new ObjectStorageException(ObjectStorageException.Reason.UNAVAILABLE,
                "Object storage is not configured.");
    }
}
