package com.knowagent.workspace.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MinioObjectStoragePropertiesTest {

    @Test
    void toStringRedactsBothCredentials() {
        MinioObjectStorageProperties properties = new MinioObjectStorageProperties(
                "http://localhost:9000", "access-secret", "super-secret", "knowledge", "us-east-1");

        assertThat(properties.toString())
                .contains("endpoint=http://localhost:9000", "bucket=knowledge", "region=us-east-1")
                .contains("accessKey=[REDACTED]", "secretKey=[REDACTED]")
                .doesNotContain("access-secret", "super-secret");
    }
}
