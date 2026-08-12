package com.knowagent.security.context;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.security.principal.TenantPrincipal;

import java.util.Objects;
import java.util.Optional;

/**
 * Request-scoped tenant context for the current thread.
 *
 * <p>Holds the authenticated {@link TenantPrincipal} for the duration of a single
 * request. The value is deliberately a plain {@link ThreadLocal}:
 * <ul>
 *   <li>{@code InheritableThreadLocal} is forbidden so that values never propagate
 *       across pooled threads (Worker executors, SSE async dispatch) and silently
 *       leak one tenant's identity into another tenant's work.</li>
 *   <li>The context is <em>fail closed</em>: {@link #requireTenantId()} throws when
 *       no principal is present, so a protected business query is rejected instead
 *       of accidentally querying across all tenants.</li>
 * </ul>
 *
 * <p>Only the web filter in {@code knowagent-api} is allowed to set and clear this
 * context. Controllers and application services must never call {@link #set} or
 * {@link #clear} directly; the filter always clears in a {@code finally} block so
 * a reused Servlet thread never carries a stale tenant into the next request.
 */
public final class TenantContext {

    private static final ThreadLocal<TenantPrincipal> HOLDER = new ThreadLocal<>();

    private TenantContext() {
    }

    /** Installs the given principal for the current thread. Rejects {@code null}. */
    public static void set(TenantPrincipal principal) {
        HOLDER.set(Objects.requireNonNull(principal, "principal must not be null"));
    }

    /** Removes the principal from the current thread. Always safe to call. */
    public static void clear() {
        HOLDER.remove();
    }

    public static Optional<TenantPrincipal> getPrincipal() {
        return Optional.ofNullable(HOLDER.get());
    }

    public static boolean isSet() {
        return HOLDER.get() != null;
    }

    /**
     * Returns the principal or throws {@link BusinessException} with
     * {@link ErrorCode#AUTHENTICATION_REQUIRED} when no tenant context exists.
     */
    public static TenantPrincipal requirePrincipal() {
        TenantPrincipal principal = HOLDER.get();
        if (principal == null) {
            throw new BusinessException(
                    ErrorCode.AUTHENTICATION_REQUIRED,
                    "No tenant context is available for this operation. "
                            + "A tenant-scoped query cannot run without an authenticated tenant.");
        }
        return principal;
    }

    /** Fail-closed accessor used by the tenant line interceptor and application code. */
    public static TenantId requireTenantId() {
        return requirePrincipal().tenantId();
    }
}
