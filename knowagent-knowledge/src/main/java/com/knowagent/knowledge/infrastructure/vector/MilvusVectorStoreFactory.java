package com.knowagent.knowledge.infrastructure.vector;

import com.knowagent.knowledge.vector.VectorStoreGateway;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;
import java.util.Objects;

/**
 * Programmatic entry point for composing the Milvus vector store outside Spring
 * (integration tests, CLI tooling). It wires the same components
 * {@link VectorStoreConfiguration} wires: one SDK access seam shared by the
 * gateway and the collection initializer, one call executor with per-operation
 * timeouts and one metrics sink.
 */
public final class MilvusVectorStoreFactory {

    private MilvusVectorStoreFactory() {
    }

    /**
     * Creates the SDK client with a bounded connect retry. {@code MilvusClientV2}
     * connects eagerly in its constructor and a freshly started Milvus proxy
     * (compose health gate) may not be ready yet, so we retry within the init
     * budget before giving up. After the budget the last failure is rethrown and
     * startup is refused.
     */
    public static MilvusClientV2 connect(ConnectConfig config, Duration totalBudget) {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(totalBudget, "totalBudget must not be null");
        long deadline = System.nanoTime() + totalBudget.toNanos();
        int attempt = 0;
        while (true) {
            try {
                return new MilvusClientV2(config);
            } catch (RuntimeException failure) {
                attempt++;
                if (System.nanoTime() >= deadline) {
                    throw failure;
                }
                long delayMillis = Math.min(2_000L, 500L * attempt);
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "Interrupted while waiting for the Milvus proxy to become ready.", interrupted);
                }
            }
        }
    }

    public static MilvusVectorStoreComponents create(MilvusClientV2 client, MilvusVectorProperties properties,
                                                     MeterRegistry meterRegistry) {
        Objects.requireNonNull(client, "client must not be null");
        Objects.requireNonNull(properties, "properties must not be null");
        VectorMetrics metrics = new VectorMetrics(meterRegistry);
        MilvusCallExecutor executor = new MilvusCallExecutor(metrics);
        SdkMilvusClientAccess access = new SdkMilvusClientAccess(client);
        return new MilvusVectorStoreComponents(
                new MilvusVectorStoreAdapter(access, properties, executor, metrics),
                new MilvusCollectionInitializer(access, properties, executor),
                executor);
    }

    /** Gateway + collection initializer sharing one call executor; close releases it. */
    public static final class MilvusVectorStoreComponents implements AutoCloseable {
        private final VectorStoreGateway gateway;
        private final MilvusCollectionInitializer initializer;
        private final MilvusCallExecutor executor;

        MilvusVectorStoreComponents(VectorStoreGateway gateway, MilvusCollectionInitializer initializer,
                                    MilvusCallExecutor executor) {
            this.gateway = gateway;
            this.initializer = initializer;
            this.executor = executor;
        }

        public VectorStoreGateway gateway() {
            return gateway;
        }

        public MilvusCollectionInitializer initializer() {
            return initializer;
        }

        @Override
        public void close() {
            executor.close();
        }
    }
}
