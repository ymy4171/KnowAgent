package com.knowagent.knowledge.chunk;

import com.knowagent.knowledge.document.ParsedDocument;

import java.util.List;

public interface Chunker {

    List<ChunkDraft> split(ParsedDocument document, ChunkPolicy policy);
}

