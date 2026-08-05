package com.knowagent.workspace.storage;

import java.io.InputStream;

public interface ObjectStorageGateway {

    StoredObject put(StorageCommand command);

    InputStream get(ObjectKey key);

    void delete(ObjectKey key);
}

