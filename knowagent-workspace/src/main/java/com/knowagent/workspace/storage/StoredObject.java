package com.knowagent.workspace.storage;

public record StoredObject(
        ObjectKey key,
        String contentType,
        long size,
        String sha256
) {
}

