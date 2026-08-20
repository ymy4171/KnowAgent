package com.knowagent.knowledge.infrastructure.vector;

import org.springframework.context.SmartLifecycle;

import java.util.Objects;

/**
 * Idempotently provisions the Milvus collection at startup (SmartLifecycle):
 *
 * <ol>
 *   <li>when the collection does not exist: create it with the fixed schema and
 *       create the configured COSINE index;</li>
 *   <li>when it already exists: describe the schema and index and refuse startup
 *       with VECTOR_SCHEMA_MISMATCH on any incompatibility - an existing production
 *       collection is never dropped or altered.</li>
 *   <li>after either path: load the compatible collection before startup completes.</li>
 * </ol>
 *
 * <p>Running as a SmartLifecycle means a missing/unreachable Milvus or an
 * incompatible schema fails the application context (startup is refused) instead of
 * surfacing on the first write. Messages stay generic and never carry credentials.
 */
public final class MilvusCollectionInitializer implements SmartLifecycle {

    private final MilvusClientAccess client;
    private final MilvusVectorProperties properties;
    private final MilvusCallExecutor executor;
    private volatile boolean running;

    public MilvusCollectionInitializer(MilvusClientAccess client, MilvusVectorProperties properties,
                                       MilvusCallExecutor executor) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
    }

    @Override
    public void start() {
        String collection = properties.collectionName();
        executor.call(collection, "init", properties.initTimeout(), () -> {
            if (client.hasCollection(collection)) {
                validateExisting(collection);
            } else {
                createAndIndex(collection);
            }
            client.loadCollection(collection, properties.initTimeout().toMillis());
            return null;
        });
        running = true;
    }

    private void createAndIndex(String collection) {
        client.createCollection(MilvusCollectionSchema.build(properties));
        long initTimeoutMillis = properties.initTimeout().toMillis();
        client.createCollectionIndex(collection, MilvusIndexParams.buildIndex(properties), initTimeoutMillis);
    }

    private void validateExisting(String collection) {
        MilvusSchemaValidator.validate(
                client.describeCollection(collection),
                client.describeIndex(collection),
                properties);
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }
}
