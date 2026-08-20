package com.knowagent.model.provider;

/**
 * Provider health state, matching the uppercase {@code health_status} CHECK values.
 * It stays {@link #UNKNOWN} until a real adapter health probe is wired; the
 * management endpoint never fabricates {@link #HEALTHY}.
 */
public enum HealthStatus {
    UNKNOWN,
    HEALTHY,
    UNHEALTHY
}
