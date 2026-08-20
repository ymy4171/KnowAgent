package com.knowagent.workspace.storage;

import com.knowagent.common.tenant.TenantId;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class ObjectStorageCommandTest {

    private static final TenantId TENANT_ID = TenantId.of(UUID.randomUUID());
    private static final ObjectKey OBJECT_KEY = new ObjectKey("documents/guide.pdf");

    @Test
    void everyStorageOperationCarriesTenantIdentity() {
        var put = new PutObjectCommand(
                TENANT_ID,
                OBJECT_KEY,
                "application/pdf",
                3,
                new ByteArrayInputStream(new byte[]{1, 2, 3})
        );
        var get = new GetObjectCommand(TENANT_ID, OBJECT_KEY);
        var delete = new DeleteObjectCommand(TENANT_ID, OBJECT_KEY);

        assertThat(put.tenantId()).isEqualTo(TENANT_ID);
        assertThat(get.tenantId()).isEqualTo(TENANT_ID);
        assertThat(delete.tenantId()).isEqualTo(TENANT_ID);
    }

    @Test
    void rejectsCommandsWithoutTenantIdentity() {
        assertThatNullPointerException().isThrownBy(() -> new GetObjectCommand(null, OBJECT_KEY));
        assertThatNullPointerException().isThrownBy(() -> new DeleteObjectCommand(null, OBJECT_KEY));
        assertThatNullPointerException().isThrownBy(() -> new PutObjectCommand(
                null,
                OBJECT_KEY,
                "application/pdf",
                0,
                new ByteArrayInputStream(new byte[0])
        ));
    }

    @Test
    void rejectsInvalidUploadMetadata() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PutObjectCommand(
                TENANT_ID,
                OBJECT_KEY,
                " ",
                0,
                new ByteArrayInputStream(new byte[0])
        ));
        assertThatIllegalArgumentException().isThrownBy(() -> new PutObjectCommand(
                TENANT_ID,
                OBJECT_KEY,
                "application/pdf",
                -1,
                new ByteArrayInputStream(new byte[0])
        ));
    }

    @Test
    void sha256IsOptionalButStrictlyValidatedWhenProvided() {
        var withoutSha = new PutObjectCommand(
                TENANT_ID, OBJECT_KEY, "application/pdf", 3,
                new ByteArrayInputStream(new byte[]{1, 2, 3}));
        assertThat(withoutSha.sha256()).isNull();

        var withSha = new PutObjectCommand(
                TENANT_ID, OBJECT_KEY, "application/pdf", 3,
                new ByteArrayInputStream(new byte[]{1, 2, 3}),
                "0123456789abcdef".repeat(4));
        assertThat(withSha.sha256()).isEqualTo("0123456789abcdef".repeat(4));

        assertThatIllegalArgumentException().isThrownBy(() -> new PutObjectCommand(
                TENANT_ID, OBJECT_KEY, "application/pdf", 3,
                new ByteArrayInputStream(new byte[]{1, 2, 3}), "not-hex"));
    }
}
