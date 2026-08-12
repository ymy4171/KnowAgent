package com.knowagent.security.domain.role;

import java.util.Set;

/**
 * Centrally defined, stable permission codes.
 *
 * <p>Permission codes are stored as a JSONB string array on {@code roles} and are
 * matched by authorization code. They must never be duplicated as inline string
 * literals across the code base; every authorization decision and every role that
 * assigns permissions references the constants here. Codes are uppercase and stable:
 * renaming one is a migration-sensitive change, so treat additions as the normal
 * evolution path.
 */
public final class SecurityPermissions {

    private SecurityPermissions() {
    }

    // Tenant and department administration.
    public static final String TENANT_READ = "TENANT_READ";
    public static final String TENANT_WRITE = "TENANT_WRITE";
    public static final String DEPARTMENT_READ = "DEPARTMENT_READ";
    public static final String DEPARTMENT_WRITE = "DEPARTMENT_WRITE";

    // Identity administration.
    public static final String USER_READ = "USER_READ";
    public static final String USER_WRITE = "USER_WRITE";
    public static final String ROLE_READ = "ROLE_READ";
    public static final String ROLE_WRITE = "ROLE_WRITE";

    // Platform administration.
    public static final String MODEL_PROVIDER_READ = "MODEL_PROVIDER_READ";
    public static final String MODEL_PROVIDER_WRITE = "MODEL_PROVIDER_WRITE";
    public static final String AUDIT_READ = "AUDIT_READ";

    /**
     * The immutable permission set granted to the bootstrap-created ADMIN system
     * role. ADMIN owns every stable administration permission defined above.
     */
    public static final Set<String> ADMIN_ROLE_PERMISSIONS = Set.of(
            TENANT_READ, TENANT_WRITE,
            DEPARTMENT_READ, DEPARTMENT_WRITE,
            USER_READ, USER_WRITE,
            ROLE_READ, ROLE_WRITE,
            MODEL_PROVIDER_READ, MODEL_PROVIDER_WRITE,
            AUDIT_READ);
}
