package com.knowagent.api.user.dto;

import java.util.List;
import java.util.Objects;

/**
 * HTTP response body for {@code GET /api/v1/users}.
 *
 * <p>Contains the requested page of users together with the total number of
 * matching users across all pages, so clients can render paged navigation.
 */
public record UserPageResponse(
        List<UserItemResponse> items,
        long total,
        int page,
        int size) {

    public UserPageResponse {
        Objects.requireNonNull(items, "items must not be null");
        items = List.copyOf(items);
        if (total < 0 || page < 1 || size < 1) {
            throw new IllegalArgumentException("total must be >= 0 and page/size must be >= 1");
        }
    }
}
