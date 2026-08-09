package com.knowagent.workspace.storage;

import java.io.InputStream;

public interface ObjectStorageGateway {

    StoredObject put(PutObjectCommand command);

    InputStream get(GetObjectCommand command);

    void delete(DeleteObjectCommand command);
}
