package com.knowagent.model.rerank;

import java.util.List;

public interface RerankGateway {

    List<RankedDocument> rerank(String query, List<String> documentIds, List<String> contents);
}

