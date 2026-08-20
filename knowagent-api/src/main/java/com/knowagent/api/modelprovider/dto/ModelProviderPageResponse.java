package com.knowagent.api.modelprovider.dto;

import java.util.List;

/** Paged model-provider listing plus the total for the same filter. */
public record ModelProviderPageResponse(List<ModelProviderResponse> items, long total, int page, int size) {
}
