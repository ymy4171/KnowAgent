package com.knowagent.extension.tool;

import com.knowagent.common.tenant.TenantId;

import java.util.Set;
import java.util.UUID;

public record ToolScope(
        TenantId tenantId,
        UUID userId,
        UUID agentId,
        Set<String> activeSkills
) {

    public ToolScope {
        activeSkills = Set.copyOf(activeSkills);
    }
}

