package com.knowagent.security.infrastructure.persistence.config;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.knowagent.security.context.TenantContext;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;

import java.util.Locale;
import java.util.Set;

/**
 * Tenant-line handler backed by {@link TenantContext}.
 *
 * <p>Every SQL statement that touches a tenant-scoped table and is not annotated
 * with {@code @InterceptorIgnore(tenantLine = "1")} is rewritten by
 * {@link com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor}.
 * The handler fails closed: {@link #getTenantId()} reads
 * {@link TenantContext#requireTenantId()}, so a protected query with no tenant
 * context throws instead of querying across all tenants.
 *
 * <p>Tables without a {@code tenant_id} column are ignored explicitly:
 * <ul>
 *   <li>{@code tenants} - the tenant root table, the only business table without
 *       {@code tenant_id}.</li>
 *   <li>{@code flyway_schema_history} - Flyway's own schema history table.</li>
 * </ul>
 */
public final class TenantContextTenantLineHandler implements TenantLineHandler {

    private static final Set<String> ROOT_TABLES = Set.of("tenants", "flyway_schema_history");

    @Override
    public Expression getTenantId() {
        return new StringValue(TenantContext.requireTenantId().value().toString());
    }

    @Override
    public String getTenantIdColumn() {
        return "tenant_id";
    }

    @Override
    public boolean ignoreTable(String tableName) {
        return tableName != null && ROOT_TABLES.contains(tableName.toLowerCase(Locale.ROOT));
    }
}
