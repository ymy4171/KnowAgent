package com.knowagent.security.application.port.out;

import com.knowagent.common.tenant.TenantId;
import com.knowagent.security.domain.role.Role;

import java.util.List;
import java.util.UUID;

public interface RoleRepository {
    List<Role> findEffectiveByUser(TenantId tenantId, UUID userId);
}
