package com.knowagent.extension.tool;

import java.util.UUID;

public record ToolInvocation(
        UUID runId,
        String toolName,
        String input
) {
}

