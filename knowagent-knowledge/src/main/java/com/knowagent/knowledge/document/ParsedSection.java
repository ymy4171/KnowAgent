package com.knowagent.knowledge.document;

public record ParsedSection(
        String heading,
        String content,
        Integer pageNumber
) {
}

