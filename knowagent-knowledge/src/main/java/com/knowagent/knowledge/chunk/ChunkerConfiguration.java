package com.knowagent.knowledge.chunk;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Production wiring for the deterministic tokenizer/chunker pair. */
@Configuration(proxyBeanMethods = false)
public class ChunkerConfiguration {

    @Bean
    TokenCounter deterministicTokenCounter() {
        return new DeterministicTokenCounter();
    }

    @Bean
    Chunker deterministicChunker(TokenCounter tokenCounter) {
        return new DeterministicChunker(tokenCounter);
    }
}
