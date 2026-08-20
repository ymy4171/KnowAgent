package com.knowagent.model.provider;

import java.util.List;

/** One page of a tenant's model providers plus the total count for the same filter. */
public record ModelProviderPage(List<ModelProvider> providers, long total) {

    public ModelProviderPage {
        if (total < 0) {
            throw new IllegalArgumentException("total must not be negative");
        }
        providers = List.copyOf(providers);
    }
}
