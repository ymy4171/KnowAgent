package com.knowagent.api.database;

import com.knowagent.common.tenant.TenantId;
import com.knowagent.workspace.storage.DeleteObjectCommand;
import com.knowagent.workspace.storage.GetObjectCommand;
import com.knowagent.workspace.storage.MinioObjectStorageAdapter;
import com.knowagent.workspace.storage.ObjectKey;
import com.knowagent.workspace.storage.ObjectStorageException;
import com.knowagent.workspace.storage.ObjectStorageGateway;
import com.knowagent.workspace.storage.PutObjectCommand;
import com.knowagent.workspace.storage.StorageKeys;
import com.knowagent.workspace.storage.StoredObject;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the {@link MinioObjectStorageAdapter} against a real MinIO server
 * (Testcontainers, pinned to the same image as docker-compose). It locks the storage
 * boundary contract: round-trip put/stat/get/delete with stored SHA-256 metadata,
 * tenant prefix enforcement (a caller can never address another tenant's object), the
 * server-generated key shape, and idempotent delete of a missing object.
 */
@Testcontainers
class MinioStorageIT {

    private static final String BUCKET = "knowledge";
    private static final TenantId TENANT_A = TenantId.of(UUID.randomUUID());
    private static final TenantId TENANT_B = TenantId.of(UUID.randomUUID());

    @Container
    static final MinIOContainer MINIO = new MinIOContainer("minio/minio:RELEASE.2023-03-20T20-16-18Z")
            .withUserName("knowagent")
            .withPassword("knowagent_dev");

    private static ObjectStorageGateway storage;
    private static MinioClient client;

    @BeforeAll
    static void provision() {
        client = MinioClient.builder()
                .endpoint(MINIO.getS3URL())
                .credentials(MINIO.getUserName(), MINIO.getPassword())
                .build();
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
            }
        } catch (Exception exception) {
            throw new IllegalStateException("MinIO bucket could not be provisioned for the test", exception);
        }
        storage = new MinioObjectStorageAdapter(client, BUCKET);
    }

    @AfterAll
    static void closeClient() {
        if (client != null) {
            client = null;
        }
    }

    @Test
    void putStatGetDeleteRoundTripsWithStoredSha256Metadata() throws Exception {
        ObjectKey key = StorageKeys.knowledgeFileSource(TENANT_A, UUID.randomUUID(), UUID.randomUUID());
        byte[] bytes = "object storage round trip\n".getBytes(StandardCharsets.UTF_8);

        StoredObject stored = storage.put(new PutObjectCommand(TENANT_A, key, "text/plain",
                bytes.length, new ByteArrayInputStream(bytes), sha256(bytes)));
        assertThat(stored.sha256()).isEqualTo(sha256(bytes));

        StoredObject stat = storage.stat(new GetObjectCommand(TENANT_A, key));
        assertThat(stat.size()).isEqualTo(bytes.length);
        assertThat(stat.contentType()).isEqualTo("text/plain");
        assertThat(stat.sha256()).isEqualTo(sha256(bytes));

        try (InputStream in = storage.get(new GetObjectCommand(TENANT_A, key))) {
            assertThat(in.readAllBytes()).isEqualTo(bytes);
        }

        storage.delete(new DeleteObjectCommand(TENANT_A, key));
        assertNotFound(() -> storage.stat(new GetObjectCommand(TENANT_A, key)));
        assertNotFound(() -> storage.get(new GetObjectCommand(TENANT_A, key)));
    }

    @Test
    void tenantAKeyCarriesTheTenantPrefixAndTenantBAddressingItIsRefused() {
        ObjectKey keyA = StorageKeys.knowledgeFileSource(TENANT_A, UUID.randomUUID(), UUID.randomUUID());
        assertThat(keyA.value()).startsWith("tenants/" + TENANT_A.value() + "/knowledge-bases/");

        storage.put(new PutObjectCommand(TENANT_A, keyA, "text/plain", 1,
                new ByteArrayInputStream(new byte[1])));

        // Tenant B cannot stat, read or delete an object under tenant A's prefix: the
        // adapter refuses at the boundary before any MinIO call.
        assertInvalidOperation(() -> storage.stat(new GetObjectCommand(TENANT_B, keyA)));
        assertInvalidOperation(() -> storage.get(new GetObjectCommand(TENANT_B, keyA)));
        assertInvalidOperation(() -> storage.delete(new DeleteObjectCommand(TENANT_B, keyA)));

        // The object is untouched and still readable by its owner.
        assertThat(storage.stat(new GetObjectCommand(TENANT_A, keyA)).size()).isEqualTo(1);
    }

    @Test
    void tenantBKeyIsRefusedWhenAddressedByTenantA() {
        ObjectKey keyB = StorageKeys.knowledgeFileSource(TENANT_B, UUID.randomUUID(), UUID.randomUUID());
        assertThat(keyB.value()).startsWith("tenants/" + TENANT_B.value() + "/");

        assertInvalidOperation(() -> storage.put(new PutObjectCommand(TENANT_A, keyB, "text/plain", 1,
                new ByteArrayInputStream(new byte[1]))));
        assertInvalidOperation(() -> storage.delete(new DeleteObjectCommand(TENANT_A, keyB)));
    }

    @Test
    void keysOutsideTheTenantPrefixAreRefusedForEveryOperation() {
        ObjectKey rogue = new ObjectKey("unknown/" + UUID.randomUUID());

        assertInvalidOperation(() -> storage.put(new PutObjectCommand(TENANT_A, rogue, "text/plain", 1,
                new ByteArrayInputStream(new byte[1]))));
        assertInvalidOperation(() -> storage.stat(new GetObjectCommand(TENANT_A, rogue)));
        assertInvalidOperation(() -> storage.get(new GetObjectCommand(TENANT_A, rogue)));
        assertInvalidOperation(() -> storage.delete(new DeleteObjectCommand(TENANT_A, rogue)));
    }

    @Test
    void deletingAMissingObjectIsAnIdempotentSuccess() {
        ObjectKey missing = StorageKeys.knowledgeFileSource(TENANT_A, UUID.randomUUID(), UUID.randomUUID());
        // Neither the first nor a repeated delete raises; the adapter treats absence
        // as a successful outcome (at-most-once compensation semantics).
        storage.delete(new DeleteObjectCommand(TENANT_A, missing));
        storage.delete(new DeleteObjectCommand(TENANT_A, missing));
    }

    private static void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ObjectStorageException.class)
                .satisfies(e -> assertThat(((ObjectStorageException) e).reason())
                        .isEqualTo(ObjectStorageException.Reason.OBJECT_NOT_FOUND));
    }

    private static void assertInvalidOperation(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ObjectStorageException.class)
                .satisfies(e -> assertThat(((ObjectStorageException) e).reason())
                        .isEqualTo(ObjectStorageException.Reason.INVALID_OPERATION));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
