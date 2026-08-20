package com.knowagent.workspace.storage;

import java.io.InputStream;

public interface ObjectStorageGateway {

    StoredObject put(PutObjectCommand command);

    /**
     * Reads object metadata without downloading the content. A missing object
     * surfaces as an {@link ObjectStorageException} with reason
     * {@link ObjectStorageException.Reason#OBJECT_NOT_FOUND}, never null.
     */
    StoredObject stat(GetObjectCommand command);

    /**
     * Returns a closeable stream over the object content. A missing object surfaces
     * as an {@link ObjectStorageException} with reason
     * {@link ObjectStorageException.Reason#OBJECT_NOT_FOUND}. The caller owns the
     * stream and must close it.
     */
    InputStream get(GetObjectCommand command);

    /**
     * Deletes the object; deleting an object that does not exist is an idempotent
     * success, matching the ingestion flow's at-most-once compensation semantics.
     */
    void delete(DeleteObjectCommand command);
}
