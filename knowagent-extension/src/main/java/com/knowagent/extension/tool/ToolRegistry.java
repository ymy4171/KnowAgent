package com.knowagent.extension.tool;

import java.util.List;

public interface ToolRegistry {

    List<ToolDefinition> resolve(ToolScope scope);

    ToolResult invoke(ToolScope scope, ToolInvocation invocation);
}

