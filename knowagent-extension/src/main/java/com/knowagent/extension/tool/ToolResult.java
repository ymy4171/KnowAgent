package com.knowagent.extension.tool;

public record ToolResult(
        boolean successful,
        String output,
        String errorCode
) {
}

