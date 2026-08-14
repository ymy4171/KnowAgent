package com.knowagent.security.application.service;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.security.application.port.out.PasswordHasher;
import com.knowagent.security.application.port.out.RoleRepository;
import com.knowagent.security.application.port.out.TenantRepository;
import com.knowagent.security.application.port.out.UserRepository;
import com.knowagent.security.domain.role.Role;
import com.knowagent.security.domain.tenant.Tenant;
import com.knowagent.security.domain.user.User;
import com.knowagent.security.principal.TenantPrincipal;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Authenticates a local user against its tenant and produces the login result.
 *
 * <p>The flow is deliberately <em>not</em> transactional. Reads are plain lookups and
 * the two writes each run in their own single-connection transaction: a successful
 * login commits its state update and Refresh Token together in
 * {@link LoginSuccessHandler}, and a failed login records its count in
 * {@link LoginFailureRecorder}. Because no nested transaction ever acquires a second
 * connection, concurrent logins hold at most one connection each and cannot exhaust
 * the pool. Everything runs before authentication exists, so no
 * {@code TenantContext} is present; every read carries an explicit
 * {@code tenant_id} and writes are scoped by an explicit tenant condition / explicit
 * PO {@code tenantId}.
 *
 * <p>Failure policy:
 * <ul>
 *   <li>An unknown tenant, an unknown user and a wrong password all throw
 *       {@link ErrorCode#INVALID_CREDENTIALS}, so callers cannot tell which part
 *       of the credentials failed. An unknown tenant or user still pays the same
 *       work as a known account: one tenant lookup, one user lookup (against the
 *       fixed {@link #dummyTenantId() dummy tenant} when the tenant is unknown) and
 *       one Argon2 verification, so response timing cannot reveal whether the
 *       account exists.</li>
 *   <li>Disabled accounts and locked accounts throw {@link ErrorCode#ACCOUNT_DISABLED} /
 *       {@link ErrorCode#ACCOUNT_LOCKED} before the password is ever checked. A
 *       {@code LOCKED} status is only ever produced by {@link LoginFailureRecorder}
 *       together with a temporary lock window; once that window has passed the
 *       account is retryable again and a successful login resets the state.</li>
 *   <li>Wrong passwords increment the failed count; once the configured threshold
 *       is reached the account is set {@code LOCKED} with a temporary lock window.
 *       A successful login clears the count and any expired lock. The failed count
 *       is recorded in an independent transaction, so it survives this method
 *       throwing, and the count increments atomically in the database so concurrent
 *       attempts never lose a count.</li>
 * </ul>
 *
 * <p>The Refresh Token raw value is high-entropy random, appears exactly once in
 * the response, and only its SHA-256 hex hash is persisted. Neither the raw value
 * nor the raw password ever reach logs, exceptions or the database.
 */
@Service
public class LoginService implements Login {

    private final TenantRepository tenants;
    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordHasher passwordHasher;
    private final LoginFailureRecorder failureRecorder;
    private final LoginSuccessHandler successHandler;
    /**
     * Precomputed hash of a throwaway value, verified instead of a real account's
     * hash when the tenant or user does not exist so the response timing cannot
     * reveal whether the account exists.
     */
    private final String dummyPasswordHash;

    public LoginService(
            TenantRepository tenants,
            UserRepository users,
            RoleRepository roles,
            PasswordHasher passwordHasher,
            LoginFailureRecorder failureRecorder,
            LoginSuccessHandler successHandler) {
        this.tenants = Objects.requireNonNull(tenants, "tenants must not be null");
        this.users = Objects.requireNonNull(users, "users must not be null");
        this.roles = Objects.requireNonNull(roles, "roles must not be null");
        this.passwordHasher = Objects.requireNonNull(passwordHasher, "passwordHasher must not be null");
        this.failureRecorder = Objects.requireNonNull(failureRecorder, "failureRecorder must not be null");
        this.successHandler = Objects.requireNonNull(successHandler, "successHandler must not be null");
        // One Argon2 cost paid up front; the raw dummy password is discarded.
        this.dummyPasswordHash = passwordHasher.encode(generateDummyPassword());
    }

    @Override
    public LoginResult login(LoginCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String tenantSlug = normalize(command.tenantSlug());
        String loginName = normalize(command.loginName());
        Instant now = Instant.now();

        Tenant tenant = tenants.findActiveBySlug(tenantSlug).orElse(null);
        User user = users.findByTenantAndLoginName(
                tenant != null ? tenant.id() : dummyTenantId(), loginName).orElse(null);
        if (tenant == null || user == null) {
            // Equalize the Argon2 cost so an attacker cannot distinguish an unknown
            // tenant or user from a wrong password by response timing alone; the
            // dummy-tenant user lookup above keeps the query count equal as well.
            passwordHasher.matches(command.password(), dummyPasswordHash);
            throw invalidCredentials();
        }

        AccountAuthenticationPolicy.requireLoginAllowed(user, now);

        if (!passwordHasher.matches(command.password(), user.passwordHash())) {
            // Records the failure in its own transaction: login() throws right after
            // and the independent transaction keeps the failed count.
            failureRecorder.recordFailedAttempt(user, now);
            throw invalidCredentials();
        }

        List<Role> effectiveRoles = roles.findEffectiveByUser(tenant.id(), user.id());
        Set<String> roleCodes = new LinkedHashSet<>();
        Set<String> permissions = new LinkedHashSet<>();
        for (Role role : effectiveRoles) {
            roleCodes.add(role.code());
            permissions.addAll(role.permissions());
        }

        LoginSuccessHandler.LoginSuccess success = successHandler.recordSuccess(user, command, now);
        TenantPrincipal principal = new TenantPrincipal(tenant.id(), user.id(), roleCodes, permissions);
        return new LoginResult(principal, permissions, success.rawValue(), success.refreshToken().expiresAt());
    }

    /**
     * A fixed tenant id used only to run the user lookup when the tenant itself
     * does not exist, so an unknown tenant costs the same two queries as a known
     * tenant with an unknown user. It never matches a real row.
     */
    static TenantId dummyTenantId() {
        return TenantId.of(UUID.fromString("00000000-0000-0000-0000-000000000000"));
    }

    /** A throwaway value hashed once for {@link #dummyPasswordHash}; never persisted. */
    private static String generateDummyPassword() {
        byte[] random = new byte[24];
        new SecureRandom().nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static BusinessException invalidCredentials() {
        return new BusinessException(
                ErrorCode.INVALID_CREDENTIALS, "Invalid tenant, user name or password.");
    }
}
