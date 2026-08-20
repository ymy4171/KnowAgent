package com.knowagent.model.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.model.crypto.EncryptedSecret;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A tenant's model-provider configuration. {@code secret} and {@code headers} carry
 * only the encrypted envelopes - plaintext never enters the domain - and
 * {@link #toString()} omits them entirely, so neither a ciphertext nor a key version
 * can leak through a log line or exception.
 */
public record ModelProvider(
        UUID id,
        TenantId tenantId,
        String providerKey,
        String displayName,
        AdapterType adapterType,
        String baseUrl,
        String embeddingBaseUrl,
        String rerankBaseUrl,
        EncryptedSecret secret,
        EncryptedSecret headers,
        Set<ModelCapability> capabilities,
        List<EnabledModel> enabledModels,
        JsonNode publicConfig,
        boolean enabled,
        HealthStatus healthStatus,
        long configVersion,
        UUID createdBy,
        UUID updatedBy,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {

    private static final Pattern PROVIDER_KEY = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,98}$");

    public ModelProvider {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(adapterType, "adapterType must not be null");
        Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        Objects.requireNonNull(healthStatus, "healthStatus must not be null");
        Objects.requireNonNull(publicConfig, "publicConfig must not be null");
        Objects.requireNonNull(capabilities, "capabilities must not be null");
        Objects.requireNonNull(enabledModels, "enabledModels must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (!PROVIDER_KEY.matcher(providerKey).matches()) {
            throw new IllegalArgumentException(
                    "providerKey must be lowercase and match [a-z0-9][a-z0-9_-]{0,98}");
        }
        if (configVersion <= 0) {
            throw new IllegalArgumentException("configVersion must be > 0");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        if (!publicConfig.isObject()) {
            throw new IllegalArgumentException("publicConfig must be a JSON object");
        }
        for (EnabledModel enabledModel : enabledModels) {
            if (!capabilities.contains(enabledModel.capability())) {
                throw new IllegalArgumentException("enabled model capability must be declared by the provider");
            }
        }
        if (secret != null && headers != null && secret.keyVersion() != headers.keyVersion()) {
            throw new IllegalArgumentException("secret and headers must share the same key version");
        }
        capabilities = Set.copyOf(capabilities);
        enabledModels = List.copyOf(enabledModels);
    }

    /** Whether this provider has an encrypted API-key secret stored. */
    public boolean hasSecret() {
        return secret != null;
    }

    /** Normalizes a raw provider key to the canonical lowercase form enforced by the DB CHECK. */
    public static String normalizeProviderKey(String raw) {
        return raw == null ? null : raw.trim().toLowerCase(Locale.ROOT);
    }

    /** Whether a normalized provider key satisfies the {@code provider_key} DB regex. */
    public static boolean isValidProviderKey(String key) {
        return key != null && PROVIDER_KEY.matcher(key).matches();
    }

    @Override
    public String toString() {
        return "ModelProvider[id=" + id + ", tenantId=" + tenantId + ", providerKey=" + providerKey
                + ", adapterType=" + adapterType + ", enabled=" + enabled + ", version=" + version + "]";
    }
}
