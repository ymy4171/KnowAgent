package com.knowagent.worker.stream;

import com.knowagent.common.tenant.TenantId;
import com.knowagent.security.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerTenantScopeTest {

    private final WorkerTenantScope scope = new WorkerTenantScope();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void tenantAThenTenantBNeverRetainsThePreviousContext() {
        TenantId tenantA = TenantId.of(UUID.randomUUID());
        TenantId tenantB = TenantId.of(UUID.randomUUID());

        assertThat(scope.call(tenantA, TenantContext::requireTenantId)).isEqualTo(tenantA);
        assertThat(TenantContext.isSet()).isFalse();
        assertThat(scope.call(tenantB, TenantContext::requireTenantId)).isEqualTo(tenantB);
        assertThat(TenantContext.isSet()).isFalse();
    }

    @Test
    void failureStillClearsThePooledThread() {
        assertThatThrownBy(() -> scope.call(TenantId.of(UUID.randomUUID()), () -> {
            assertThat(TenantContext.isSet()).isTrue();
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(TenantContext.isSet()).isFalse();
    }
}
