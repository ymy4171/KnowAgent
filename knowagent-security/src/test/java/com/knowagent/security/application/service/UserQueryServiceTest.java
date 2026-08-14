package com.knowagent.security.application.service;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.security.application.port.out.UserRepository;
import com.knowagent.security.domain.user.User;
import com.knowagent.security.domain.user.UserPage;
import com.knowagent.security.domain.user.UserStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserQueryServiceTest {

    private static final TenantId TENANT_A = TenantId.of(UUID.randomUUID());
    private static final TenantId TENANT_B = TenantId.of(UUID.randomUUID());
    private static final UUID USER_1 = UUID.randomUUID();
    private static final UUID USER_2 = UUID.randomUUID();

    private final RecordingUserRepository repository = new RecordingUserRepository();
    private final UserQueryService service = new UserQueryService(repository);

    @Test
    void pageUsersPassesTenantPatternStatusAndPagingToThePort() {
        repository.users.add(user(USER_1, TENANT_A, "alice"));
        repository.total = 7;

        UserPage page = service.pageUsers(TENANT_A, "  Alice  ", UserStatus.ACTIVE, 2, 5);

        assertThat(repository.lastTenantId).isEqualTo(TENANT_A);
        assertThat(repository.lastPattern).isEqualTo("%Alice%");
        assertThat(repository.lastStatus).isEqualTo(UserStatus.ACTIVE);
        assertThat(repository.lastPage).isEqualTo(2);
        assertThat(repository.lastSize).isEqualTo(5);
        assertThat(page.users()).containsExactly(repository.users.get(0));
        assertThat(page.total()).isEqualTo(7);
    }

    @Test
    void blankKeywordIsPassedThroughAsNoFilter() {
        service.pageUsers(TENANT_A, "   ", null, 1, 20);

        assertThat(repository.lastPattern).isNull();
    }

    @Test
    void keywordEscapesLikeMetacharacters() {
        assertThat(UserQueryService.buildLikePattern(null)).isNull();
        assertThat(UserQueryService.buildLikePattern("  ")).isNull();
        assertThat(UserQueryService.buildLikePattern("alice")).isEqualTo("%alice%");
        // % and _ are escaped so they match literally; \ is escaped first so the
        // other escapes are not themselves swallowed.
        assertThat(UserQueryService.buildLikePattern("a%b_c\\d")).isEqualTo("%a\\%b\\_c\\\\d%");
        // A keyword that is entirely metacharacters is still matched literally.
        assertThat(UserQueryService.buildLikePattern("%")).isEqualTo("%\\%%");
        // Non-ASCII keywords pass through untouched.
        assertThat(UserQueryService.buildLikePattern("张伟")).isEqualTo("%张伟%");
    }

    @Test
    void invalidPageAndSizeAreRejectedWithValidationError() {
        assertValidationError(() -> service.pageUsers(TENANT_A, null, null, 0, 20));
        assertValidationError(() -> service.pageUsers(TENANT_A, null, null, 1, 0));
        assertValidationError(() -> service.pageUsers(TENANT_A, null, null, 1, 101));
        assertValidationError(() -> service.pageUsers(
                TENANT_A, null, null, Integer.MAX_VALUE, UserQueryService.MAX_PAGE_SIZE));
        // Boundary values are accepted.
        service.pageUsers(TENANT_A, null, null, 1, 100);
    }

    @Test
    void tenantIsNeverDerivedFromAnythingButTheCaller() {
        // The service has no request access at all: the tenant it passes to the
        // port is exactly the tenant the caller derived from the principal.
        service.pageUsers(TENANT_B, null, null, 1, 20);
        assertThat(repository.lastTenantId).isEqualTo(TENANT_B);
    }

    @Test
    void userDetailReturnsTheUserInsideTheTenant() {
        repository.users.add(user(USER_1, TENANT_A, "alice"));

        User found = service.userDetail(TENANT_A, USER_1);

        assertThat(found.id()).isEqualTo(USER_1);
        assertThat(found.tenantId()).isEqualTo(TENANT_A);
    }

    @Test
    void userDetailReturnsNotFoundForUnknownOrCrossTenantUser() {
        repository.users.add(user(USER_1, TENANT_A, "alice"));

        assertThatThrownBy(() -> service.userDetail(TENANT_A, USER_2))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).errorCode())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        // The user exists but in another tenant: still a 404, never a leak.
        assertThatThrownBy(() -> service.userDetail(TENANT_B, USER_1))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).errorCode())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private void assertValidationError(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).errorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    private static User user(UUID id, TenantId tenantId, String loginName) {
        return new User(id, tenantId, null, loginName, loginName, null, null, null,
                "hash", UserStatus.ACTIVE, 0, null, null, null, 0L,
                Instant.EPOCH, Instant.EPOCH, null);
    }

    /** Records the last search call so the service's argument handling is verifiable. */
    private static final class RecordingUserRepository implements UserRepository {
        final List<User> users = new ArrayList<>();
        long total;
        TenantId lastTenantId;
        String lastPattern;
        UserStatus lastStatus;
        int lastPage;
        int lastSize;

        @Override
        public Optional<User> findByTenantAndLoginName(TenantId tenantId, String loginName) {
            throw new UnsupportedOperationException("not exercised by UserQueryService");
        }

        @Override
        public Optional<User> findById(TenantId tenantId, UUID userId) {
            return users.stream()
                    .filter(u -> u.tenantId().equals(tenantId) && u.id().equals(userId))
                    .findFirst();
        }

        @Override
        public boolean updateLoginState(User user) {
            throw new UnsupportedOperationException("not exercised by UserQueryService");
        }

        @Override
        public int recordLoginFailure(TenantId tenantId, UUID userId, Instant now,
                                      int maxFailedAttempts, Instant lockUntil) {
            throw new UnsupportedOperationException("not exercised by UserQueryService");
        }

        @Override
        public UserPage search(TenantId tenantId, String keywordPattern, UserStatus status,
                               int page, int size) {
            lastTenantId = tenantId;
            lastPattern = keywordPattern;
            lastStatus = status;
            lastPage = page;
            lastSize = size;
            return new UserPage(users, total);
        }
    }
}
