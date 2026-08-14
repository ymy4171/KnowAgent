package com.knowagent.security.domain.user;

import java.util.List;
import java.util.Objects;

/**
 * One page of a tenant-scoped user query together with the total number of
 * matching users across all pages.
 *
 * <p>The page and the count are produced from the same tenant, keyword and status
 * conditions, so {@link #total()} is always the count of the full filtered result
 * set that {@link #users()} is sliced from.
 */
public record UserPage(
        List<User> users,
        long total) {

    public UserPage {
        Objects.requireNonNull(users, "users must not be null");
        users = List.copyOf(users);
        if (total < 0) {
            throw new IllegalArgumentException("total must not be negative");
        }
    }
}
