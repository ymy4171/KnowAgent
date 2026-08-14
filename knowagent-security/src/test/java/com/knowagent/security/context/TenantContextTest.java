package com.knowagent.security.context;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.security.principal.TenantPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantContextTest {

    private static final UUID TENANT_A_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @AfterEach
    void clearContextAfterEach() {
        TenantContext.clear();
    }

    @Test
    void sequentialRequestsOnSameThreadDoNotResidualTenant() {
        TenantPrincipal tenantA = principal(TENANT_A_ID);
        TenantPrincipal tenantB = principal(TENANT_B_ID);

        // Simulated request 1 for tenant-A.
        TenantContext.set(tenantA);
        assertThat(TenantContext.requireTenantId().value()).isEqualTo(TENANT_A_ID);
        TenantContext.clear();

        // Simulated request 2 for tenant-B on the same (reused) thread.
        TenantContext.set(tenantB);
        assertThat(TenantContext.requireTenantId().value()).isEqualTo(TENANT_B_ID);
        TenantContext.clear();

        // The thread must be empty after both requests.
        assertThat(TenantContext.isSet()).isFalse();
        assertThat(TenantContext.getPrincipal()).isEmpty();
    }

    @Test
    void missingContextFailsClosedForProtectedAccess() {
        assertThat(TenantContext.isSet()).isFalse();

        assertThatThrownBy(TenantContext::requireTenantId)
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).errorCode())
                        .isEqualTo(ErrorCode.AUTHENTICATION_REQUIRED));
        assertThatThrownBy(TenantContext::requirePrincipal)
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).errorCode())
                        .isEqualTo(ErrorCode.AUTHENTICATION_REQUIRED));
    }

    @Test
    void setRejectsNullPrincipal() {
        assertThatThrownBy(() -> TenantContext.set(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void clearIsIdempotentAndRemovesPrincipal() {
        TenantContext.set(principal(TENANT_A_ID));
        TenantContext.clear();
        TenantContext.clear();
        assertThat(TenantContext.isSet()).isFalse();
    }

    private static TenantPrincipal principal(UUID tenantId) {
        return new TenantPrincipal(TenantId.of(tenantId), UUID.randomUUID(), Set.of("ROLE_USER"), Set.of());
    }
}
