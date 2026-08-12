package com.knowagent.security.infrastructure.persistence.config;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.security.context.TenantContext;
import com.knowagent.security.principal.TenantPrincipal;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantContextTenantLineHandlerTest {

    private static final UUID TENANT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private final TenantContextTenantLineHandler handler = new TenantContextTenantLineHandler();

    @AfterEach
    void clearContextAfterEach() {
        TenantContext.clear();
    }

    @Test
    void getTenantIdReturnsCurrentContextTenant() {
        TenantContext.set(new TenantPrincipal(
                TenantId.of(TENANT_ID), UUID.randomUUID(), Set.of("ROLE_USER")));

        Expression expression = handler.getTenantId();
        assertThat(expression).isInstanceOf(StringValue.class);
        assertThat(((StringValue) expression).getValue()).isEqualTo(TENANT_ID.toString());
    }

    @Test
    void getTenantIdWithoutContextFailsClosed() {
        assertThatThrownBy(handler::getTenantId)
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).errorCode())
                        .isEqualTo(ErrorCode.AUTHENTICATION_REQUIRED));
    }

    @Test
    void tenantColumnIsTenantId() {
        assertThat(handler.getTenantIdColumn()).isEqualTo("tenant_id");
    }

    @Test
    void rootTablesWithoutTenantColumnAreIgnored() {
        assertThat(handler.ignoreTable("tenants")).isTrue();
        assertThat(handler.ignoreTable("TENANTS")).isTrue();
        assertThat(handler.ignoreTable("flyway_schema_history")).isTrue();
        assertThat(handler.ignoreTable("FLYWAY_SCHEMA_HISTORY")).isTrue();
    }

    @Test
    void tenantScopedTablesAreNeverIgnored() {
        assertThat(handler.ignoreTable("users")).isFalse();
        assertThat(handler.ignoreTable("roles")).isFalse();
        assertThat(handler.ignoreTable("user_roles")).isFalse();
        assertThat(handler.ignoreTable("refresh_tokens")).isFalse();
        assertThat(handler.ignoreTable(null)).isFalse();
    }
}
