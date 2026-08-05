package com.knowagent.knowledge.document;

public interface DocumentParser {

    boolean supports(ParseSource source);

    ParsedDocument parse(ParseSource source);
}

