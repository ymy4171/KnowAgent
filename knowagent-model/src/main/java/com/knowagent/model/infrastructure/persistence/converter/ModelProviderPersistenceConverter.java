package com.knowagent.model.infrastructure.persistence.converter;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.model.crypto.EncryptedSecret;
import com.knowagent.model.infrastructure.persistence.entity.ModelProviderPo;
import com.knowagent.model.provider.ModelProvider;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Maps {@link ModelProviderPo} and {@link ModelProvider}. Ciphertext stays an opaque
 * {@link EncryptedSecret}; nothing here decrypts or renders it.
 */
public final class ModelProviderPersistenceConverter {

    private ModelProviderPersistenceConverter() {
    }

    public static ModelProvider toDomain(ModelProviderPo source) {
        try {
            return new ModelProvider(
                    source.getId(),
                    TenantId.of(source.getTenantId()),
                    source.getProviderKey(),
                    source.getDisplayName(),
                    source.getAdapterType(),
                    source.getBaseUrl(),
                    source.getEmbeddingBaseUrl(),
                    source.getRerankBaseUrl(),
                    encrypted(source.getSecretCiphertext(), source.getSecretKeyVersion()),
                    encrypted(source.getHeadersCiphertext(), source.getSecretKeyVersion()),
                    source.getCapabilities(),
                    source.getEnabledModels(),
                    source.getPublicConfig(),
                    Boolean.TRUE.equals(source.getEnabled()),
                    source.getHealthStatus(),
                    requiredConfigVersion(source.getConfigVersion()),
                    source.getCreatedBy(),
                    source.getUpdatedBy(),
                    requiredVersion(source.getVersion()),
                    instant(source.getCreatedAt()),
                    instant(source.getUpdatedAt()),
                    instant(source.getDeletedAt()));
        } catch (RuntimeException exception) {
            throw invalidRow(exception);
        }
    }

    public static ModelProviderPo toPersistence(ModelProvider source) {
        try {
            ModelProviderPo target = new ModelProviderPo();
            target.setId(source.id());
            target.setTenantId(source.tenantId().value());
            target.setProviderKey(source.providerKey());
            target.setDisplayName(source.displayName());
            target.setAdapterType(source.adapterType());
            target.setBaseUrl(source.baseUrl());
            target.setEmbeddingBaseUrl(source.embeddingBaseUrl());
            target.setRerankBaseUrl(source.rerankBaseUrl());
            target.setSecretCiphertext(source.secret() == null ? null : source.secret().envelope());
            target.setHeadersCiphertext(source.headers() == null ? null : source.headers().envelope());
            target.setSecretKeyVersion(sharedKeyVersion(source.secret(), source.headers()));
            target.setCapabilities(source.capabilities());
            target.setEnabledModels(source.enabledModels());
            target.setPublicConfig(source.publicConfig());
            target.setEnabled(source.enabled());
            target.setHealthStatus(source.healthStatus());
            target.setConfigVersion(source.configVersion());
            target.setCreatedBy(source.createdBy());
            target.setUpdatedBy(source.updatedBy());
            target.setVersion(source.version());
            target.setCreatedAt(offsetDateTime(source.createdAt()));
            target.setUpdatedAt(offsetDateTime(source.updatedAt()));
            target.setDeletedAt(offsetDateTime(source.deletedAt()));
            return target;
        } catch (RuntimeException exception) {
            throw invalidRow(exception);
        }
    }

    private static EncryptedSecret encrypted(String ciphertext, Integer keyVersion) {
        if (ciphertext == null) {
            return null;
        }
        if (keyVersion == null) {
            throw new IllegalArgumentException("secret_key_version must not be null when ciphertext is present");
        }
        return new EncryptedSecret(ciphertext, keyVersion);
    }

    private static Integer sharedKeyVersion(EncryptedSecret secret, EncryptedSecret headers) {
        if (secret != null) {
            return secret.keyVersion();
        }
        if (headers != null) {
            return headers.keyVersion();
        }
        return null;
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime offsetDateTime(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static long requiredVersion(Long value) {
        if (value == null) {
            throw new IllegalArgumentException("version must not be null");
        }
        return value;
    }

    private static long requiredConfigVersion(Long value) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException("config_version must be > 0");
        }
        return value;
    }

    private static BusinessException invalidRow(RuntimeException cause) {
        BusinessException exception = new BusinessException(
                ErrorCode.INTERNAL_ERROR, "Invalid model provider persistence record");
        exception.initCause(cause);
        return exception;
    }
}
