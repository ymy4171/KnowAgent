package com.knowagent.worker.stream;

import com.knowagent.common.tenant.TenantId;
import com.knowagent.security.context.TenantContext;
import com.knowagent.security.principal.TenantPrincipal;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Installs a trusted asynchronous tenant and always removes it from the pooled thread. */
@Component
public class WorkerTenantScope {

    private static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

    public <T> T call(TenantId tenantId, Supplier<T> action) {
        TenantContext.clear();
        TenantContext.set(new TenantPrincipal(tenantId, SYSTEM_ACTOR, Set.of("WORKER"), Set.of()));
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }
}
