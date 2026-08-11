package com.knowagent.security.application.port.out;

import com.knowagent.common.tenant.TenantId;
import com.knowagent.security.domain.user.User;

import java.util.Optional;

public interface UserRepository {
    Optional<User> findByTenantAndLoginName(TenantId tenantId, String loginName);
}
