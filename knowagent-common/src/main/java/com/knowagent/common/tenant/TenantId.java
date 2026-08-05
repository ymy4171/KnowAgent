package com.knowagent.common.tenant;

import java.util.Objects;
import java.util.UUID;

public record TenantId(UUID value) {

    public TenantId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static TenantId of(UUID value) {
        return new TenantId(value);
    }
}

