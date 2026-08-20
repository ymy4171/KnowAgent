package com.knowagent.workspace.storage;

import com.knowagent.common.tenant.TenantId;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StorageKeysTest {

    private static final TenantId ALPHA = TenantId.of(UUID.randomUUID());
    private static final TenantId BETA = TenantId.of(UUID.randomUUID());
    private static final UUID KB = UUID.randomUUID();
    private static final UUID FILE = UUID.randomUUID();

    @Test
    void knowledgeFileKeyIsDeterministicAndCarriesTheTenantPrefix() {
        ObjectKey key = StorageKeys.knowledgeFileSource(ALPHA, KB, FILE);
        assertThat(key.value()).isEqualTo(
                "tenants/" + ALPHA.value() + "/knowledge-bases/" + KB + "/files/" + FILE + "/source");
        assertThat(StorageKeys.isOwnedBy(ALPHA, key)).isTrue();
    }

    @Test
    void tenantOnlyOwnsKeysUnderItsOwnPrefix() {
        ObjectKey alphaKey = StorageKeys.knowledgeFileSource(ALPHA, KB, FILE);
        ObjectKey betaKey = StorageKeys.knowledgeFileSource(BETA, KB, FILE);

        assertThat(StorageKeys.isOwnedBy(BETA, alphaKey)).isFalse();
        assertThat(StorageKeys.isOwnedBy(ALPHA, betaKey)).isFalse();
        // A foreign key is not made safe by being "close" to the tenant prefix.
        assertThat(StorageKeys.isOwnedBy(ALPHA, new ObjectKey("tenants/evil/objects/x"))).isFalse();
    }
}
