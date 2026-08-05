package com.knowagent.agent.run;

import com.knowagent.common.tenant.TenantId;

import java.util.UUID;

public record AgentRunContext(
        TenantId tenantId,
        UUID userId,
        UUID agentId,
        UUID conversationId,
        UUID requestId,
        UUID runId,
        String question
) {
}

