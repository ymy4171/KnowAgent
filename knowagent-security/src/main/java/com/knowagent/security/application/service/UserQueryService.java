package com.knowagent.security.application.service;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.security.application.port.out.UserRepository;
import com.knowagent.security.domain.user.User;
import com.knowagent.security.domain.user.UserPage;
import com.knowagent.security.domain.user.UserStatus;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

/**
 * Tenant-scoped queries over a tenant's users, serving the authenticated user
 * management endpoints.
 *
 * <p>Like {@link CurrentUserService}, the tenant id is always supplied by the
 * caller from the authenticated principal - it is never parsed from a request
 * parameter. A user that cannot be found (including one belonging to another
 * tenant) is reported as {@link ErrorCode#RESOURCE_NOT_FOUND}, matching the rule
 * that a resource a caller cannot see is a 404.
 */
@Service
public class UserQueryService {

    public static final int MAX_PAGE_SIZE = 100;
    private static final long MAX_SUPPORTED_OFFSET = Integer.MAX_VALUE;

    private final UserRepository users;

    public UserQueryService(UserRepository users) {
        this.users = Objects.requireNonNull(users, "users must not be null");
    }

    /**
     * Returns one page of the tenant's non-deleted users, newest first, optionally
     * filtered by fuzzy keyword and status. Invalid paging values are rejected with
     * a 400 {@code VALIDATION_ERROR}.
     */
    public UserPage pageUsers(TenantId tenantId, String keyword, UserStatus status,
                              int page, int size) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        validatePaging(page, size);
        return users.search(tenantId, buildLikePattern(keyword), status, page, size);
    }

    /**
     * Returns one non-deleted user strictly inside the tenant, or a 404 when the
     * user does not exist (including a user id that belongs to another tenant).
     */
    public User userDetail(TenantId tenantId, UUID userId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        return users.findById(tenantId, userId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "The requested resource does not exist."));
    }

    private static void validatePaging(int page, int size) {
        if (page < 1) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "page must be >= 1");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "size must be between 1 and " + MAX_PAGE_SIZE);
        }
        long offset = (long) (page - 1) * size;
        if (offset > MAX_SUPPORTED_OFFSET) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "page and size exceed the supported paging range");
        }
    }

    /**
     * Turns a raw keyword into a case-insensitive {@code LIKE} pattern with the
     * {@code %} / {@code _} / {@code \} metacharacters escaped, so a keyword
     * containing them matches literally. A null or blank keyword yields
     * {@code null}, which the query treats as "no keyword filter".
     */
    static String buildLikePattern(String keyword) {
        if (keyword == null) {
            return null;
        }
        String trimmed = keyword.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        StringBuilder pattern = new StringBuilder("%");
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '\\' || c == '%' || c == '_') {
                pattern.append('\\');
            }
            pattern.append(c);
        }
        return pattern.append('%').toString();
    }
}
