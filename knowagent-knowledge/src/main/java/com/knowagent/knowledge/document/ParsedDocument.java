package com.knowagent.knowledge.document;

import java.util.List;

public record ParsedDocument(
        String title,
        List<ParsedSection> sections
) {

    public ParsedDocument {
        sections = List.copyOf(sections);
    }
}

