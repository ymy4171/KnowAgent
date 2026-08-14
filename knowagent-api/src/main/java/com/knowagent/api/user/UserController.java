package com.knowagent.api.user;

import com.knowagent.api.user.dto.MeResponse;
import com.knowagent.api.user.dto.UserItemResponse;
import com.knowagent.api.user.dto.UserPageResponse;
import com.knowagent.security.application.service.CurrentUser;
import com.knowagent.security.application.service.CurrentUserService;
import com.knowagent.security.application.service.UserQueryService;
import com.knowagent.security.domain.user.User;
import com.knowagent.security.domain.user.UserPage;
import com.knowagent.security.domain.user.UserStatus;
import com.knowagent.security.principal.TenantPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Authenticated user endpoints.
 *
 * <p>{@code /users/me} resolves the identity strictly from the authenticated
 * principal - the tenant id and user id never come from the client. The data is
 * loaded fresh from the database (roles and permissions are not trusted from the
 * JWT), so a revoked role or permission takes effect immediately. A tenant or user
 * that cannot be found is reported as 404, matching the cross-tenant 404 rule.
 *
 * <p>{@code /users} and {@code /users/{userId}} are the tenant-scoped user
 * management queries. They require the {@code USER_READ} permission (enforced by
 * method security) and resolve the tenant from the authenticated principal only;
 * the caller can never target another tenant. Cross-tenant user ids return 404.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final CurrentUserService currentUserService;
    private final UserQueryService userQueryService;

    public UserController(CurrentUserService currentUserService, UserQueryService userQueryService) {
        this.currentUserService = currentUserService;
        this.userQueryService = userQueryService;
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal TenantPrincipal principal) {
        CurrentUser current = currentUserService.currentUser(principal.tenantId(), principal.userId());
        return new MeResponse(
                current.userId(),
                current.tenantId().value(),
                current.tenantSlug(),
                current.loginName(),
                current.displayName(),
                current.roles(),
                current.permissions());
    }

    /**
     * Lists the caller's tenant users, newest first, with optional fuzzy keyword
     * and status filters. The tenant id comes from the authenticated principal;
     * paging defaults to page 1, size 20 and an invalid page/size yields 400.
     */
    @PreAuthorize("hasAuthority('USER_READ')")
    @GetMapping
    public UserPageResponse list(
            @AuthenticationPrincipal TenantPrincipal principal,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) UserStatus status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        UserPage result = userQueryService.pageUsers(principal.tenantId(), keyword, status, page, size);
        List<UserItemResponse> items = result.users().stream()
                .map(UserController::toItem)
                .toList();
        return new UserPageResponse(items, result.total(), page, size);
    }

    /**
     * Returns one user strictly inside the caller's tenant. A user id belonging to
     * another tenant (or unknown) is reported as 404 so resource existence is never
     * leaked.
     */
    @PreAuthorize("hasAuthority('USER_READ')")
    @GetMapping("/{userId}")
    public UserItemResponse detail(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable UUID userId) {
        return toItem(userQueryService.userDetail(principal.tenantId(), userId));
    }

    private static UserItemResponse toItem(User user) {
        return new UserItemResponse(
                user.id(),
                user.departmentId(),
                user.loginName(),
                user.displayName(),
                user.email(),
                user.phoneNumber(),
                user.status(),
                user.createdAt());
    }
}
