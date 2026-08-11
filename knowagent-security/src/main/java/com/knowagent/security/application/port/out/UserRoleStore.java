package com.knowagent.security.application.port.out;

import com.knowagent.security.domain.role.UserRole;

public interface UserRoleStore {
    void insert(UserRole userRole);
}
