package com.knowagent.model.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.model.application.port.out.ModelProviderReferenceChecker;
import com.knowagent.model.application.port.out.ModelProviderRepository;
import com.knowagent.model.crypto.EncryptedSecret;
import com.knowagent.model.crypto.SecretCipher;
import com.knowagent.model.crypto.SecretCipherException;
import com.knowagent.model.provider.HealthStatus;
import com.knowagent.model.provider.EnabledModel;
import com.knowagent.model.provider.ModelCapability;
import com.knowagent.model.provider.ModelProvider;
import com.knowagent.model.provider.ModelProviderHealthCheck;
import com.knowagent.model.provider.ModelProviderPage;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Application service for model-provider configuration.
 *
 * <p>Owns the business rules: provider-key normalization and uniqueness, secret
 * encryption (never plaintext in the domain), the keep-vs-clear secret update
 * contract, optimistic-lock conflict handling and the referenced-provider delete
 * guard. The tenant id is always supplied by the caller from the authenticated
 * principal.
 */
@Service
public class ModelProviderService {

    public static final int MAX_PAGE_SIZE = 100;
    private static final long MAX_SUPPORTED_OFFSET = Integer.MAX_VALUE;
    private static final int MAX_DISPLAY_NAME_LENGTH = 128;
    private static final int MAX_URL_LENGTH = 1024;
    private static final int MAX_SECRET_LENGTH = 16_384;
    private static final int MAX_HEADER_COUNT = 64;
    private static final int MAX_HEADER_NAME_LENGTH = 128;
    private static final int MAX_HEADER_VALUE_LENGTH = 4_096;
    private static final int MAX_PUBLIC_CONFIG_BYTES = 65_536;
    private static final int MAX_ENABLED_MODELS = 512;
    private static final Pattern HEADER_NAME = Pattern.compile("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$");
    private static final Set<String> FORBIDDEN_HEADERS = Set.of(
            "host", "content-length", "transfer-encoding", "connection");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ModelProviderRepository providers;
    private final ModelProviderReferenceChecker referenceChecker;
    private final SecretCipher secretCipher;

    public ModelProviderService(ModelProviderRepository providers,
                                ModelProviderReferenceChecker referenceChecker,
                                SecretCipher secretCipher) {
        this.providers = Objects.requireNonNull(providers, "providers must not be null");
        this.referenceChecker = Objects.requireNonNull(referenceChecker, "referenceChecker must not be null");
        this.secretCipher = Objects.requireNonNull(secretCipher, "secretCipher must not be null");
    }

    public ModelProvider create(CreateModelProviderCommand command) {
        String key = ModelProvider.normalizeProviderKey(command.providerKey());
        if (!ModelProvider.isValidProviderKey(key)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "providerKey must be lowercase and match [a-z0-9][a-z0-9_-]{0,98}");
        }
        String baseUrl = requireHttpUrl(command.baseUrl(), "baseUrl");
        String displayName = requireDisplayName(command.displayName());
        validateSecretValue(command.secret(), "secret");
        Map<String, String> headers = validateHeaders(command.headers());
        Set<ModelCapability> capabilities = command.capabilities();
        List<EnabledModel> enabledModels = command.enabledModels();
        validateModelCatalog(capabilities, enabledModels);
        JsonNode publicConfig = validatePublicConfig(command.publicConfig());
        if (providers.findActiveByKey(command.tenantId(), key).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "A model provider with this provider key already exists in the tenant.");
        }

        Instant now = Instant.now();
        ModelProvider provider = new ModelProvider(
                UUID.randomUUID(), command.tenantId(), key, displayName,
                command.adapterType(), baseUrl, optionalHttpUrl(command.embeddingBaseUrl(), "embeddingBaseUrl"),
                optionalHttpUrl(command.rerankBaseUrl(), "rerankBaseUrl"), encrypt(command.secret()),
                encryptHeaders(headers), capabilities, enabledModels, publicConfig, command.enabled(),
                HealthStatus.UNKNOWN, 1L, command.actorId(), command.actorId(), 0L, now, now, null);
        try {
            providers.save(provider);
        } catch (DuplicateKeyException exception) {
            // The partial unique index is the race backstop: two concurrent creates
            // can both pass the pre-check, but only one commits.
            throw new BusinessException(ErrorCode.CONFLICT,
                    "A model provider with this provider key already exists in the tenant.");
        }
        return provider;
    }

    @Transactional
    public ModelProvider update(UpdateModelProviderCommand command) {
        ModelProvider existing = providers.findById(command.tenantId(), command.providerId())
                .orElseThrow(() -> notFound());

        validateSecretUpdate("secret", command.secret(), command.clearSecret());
        validateSecretUpdate("headers", command.headers(), command.clearHeaders());
        validateSecretValue(command.secret(), "secret");
        Map<String, String> submittedHeaders = validateHeaders(command.headers());

        String key = existing.providerKey();
        if (command.providerKey() != null) {
            key = ModelProvider.normalizeProviderKey(command.providerKey());
            if (!ModelProvider.isValidProviderKey(key)) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "providerKey must be lowercase and match [a-z0-9][a-z0-9_-]{0,98}");
            }
            if (!key.equals(existing.providerKey())
                    && providers.findActiveByKey(command.tenantId(), key).isPresent()) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "A model provider with this provider key already exists in the tenant.");
            }
        }

        EncryptedSecret secret = existing.secret();
        if (command.clearSecret()) {
            secret = null;
        } else if (command.secret() != null) {
            secret = encrypt(command.secret());
        }
        EncryptedSecret headers = existing.headers();
        if (command.clearHeaders()) {
            headers = null;
        } else if (command.headers() != null) {
            headers = encryptHeaders(submittedHeaders);
        }

        Instant now = Instant.now();
        Set<ModelCapability> capabilities =
                command.capabilities() != null ? command.capabilities() : existing.capabilities();
        List<EnabledModel> enabledModels =
                command.enabledModels() != null ? command.enabledModels() : existing.enabledModels();
        validateModelCatalog(capabilities, enabledModels);
        JsonNode publicConfig = command.publicConfig() != null
                ? validatePublicConfig(command.publicConfig()) : existing.publicConfig();
        ModelProvider updated = new ModelProvider(
                existing.id(), existing.tenantId(), key,
                command.displayName() != null ? requireDisplayName(command.displayName())
                        : existing.displayName(),
                command.adapterType() != null ? command.adapterType() : existing.adapterType(),
                command.baseUrl() != null ? requireHttpUrl(command.baseUrl(), "baseUrl") : existing.baseUrl(),
                command.embeddingBaseUrl() != null ? optionalHttpUrl(command.embeddingBaseUrl(), "embeddingBaseUrl")
                        : existing.embeddingBaseUrl(),
                command.rerankBaseUrl() != null ? optionalHttpUrl(command.rerankBaseUrl(), "rerankBaseUrl")
                        : existing.rerankBaseUrl(),
                secret, headers,
                capabilities, enabledModels, publicConfig,
                command.enabled() != null ? command.enabled() : existing.enabled(),
                existing.healthStatus(), existing.configVersion() + 1,
                existing.createdBy(), command.actorId(), existing.version(), existing.createdAt(), now, null);

        try {
            if (providers.updateConfig(updated) == 0) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "The model provider was modified concurrently; please retry.");
            }
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "A model provider with this provider key already exists in the tenant.");
        }
        return providers.findById(command.tenantId(), command.providerId()).orElseThrow(() -> notFound());
    }

    @Transactional
    public void delete(TenantId tenantId, UUID providerId) {
        ModelProvider existing = providers.findByIdForUpdate(tenantId, providerId).orElseThrow(() -> notFound());
        if (referenceChecker.isReferencedByActiveKnowledgeBase(tenantId, providerId)) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "The model provider is still referenced by an active knowledge base.");
        }
        if (providers.softDelete(tenantId, providerId, existing.version()) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "The model provider was modified concurrently; please retry.");
        }
    }

    public ModelProvider get(TenantId tenantId, UUID providerId) {
        return providers.findById(tenantId, providerId).orElseThrow(() -> notFound());
    }

    public ModelProviderPage list(TenantId tenantId, int page, int size) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        if (page < 1) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "page must be >= 1");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "size must be between 1 and " + MAX_PAGE_SIZE);
        }
        long offset = (long) (page - 1) * size;
        if (offset > MAX_SUPPORTED_OFFSET) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "page and size exceed the supported paging range");
        }
        return providers.page(tenantId, page, size);
    }

    public ModelProviderHealthCheck healthCheck(TenantId tenantId, UUID providerId) {
        ModelProvider provider = providers.findById(tenantId, providerId).orElseThrow(() -> notFound());
        // Adapter connectivity is deliberately not wired yet: report the configuration
        // as present but never claim a verified HEALTHY result.
        return new ModelProviderHealthCheck(provider.id(), provider.healthStatus(), false,
                "Health check is not wired to a real model adapter; provider configuration is present but "
                        + "connectivity is unverified.");
    }

    private EncryptedSecret encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        if (!secretCipher.isConfigured()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "Provider secret cannot be stored: no master key is configured.");
        }
        try {
            return secretCipher.encrypt(plaintext);
        } catch (SecretCipherException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Unable to encrypt provider secret.");
        }
    }

    private EncryptedSecret encryptHeaders(Map<String, String> headers) {
        if (headers == null) {
            return null;
        }
        try {
            return encrypt(OBJECT_MAPPER.writeValueAsString(headers));
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Provider headers cannot be serialized.");
        }
    }

    private static String requireNonBlank(String value, String field) {
        String trimmed = value == null ? null : value.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, field + " must not be blank");
        }
        return trimmed;
    }

    private static String requireDisplayName(String value) {
        String displayName = requireNonBlank(value, "displayName");
        if (displayName.length() > MAX_DISPLAY_NAME_LENGTH) {
            throw validation("displayName must contain at most " + MAX_DISPLAY_NAME_LENGTH + " characters");
        }
        return displayName;
    }

    private static String requireHttpUrl(String value, String field) {
        String url = requireNonBlank(value, field);
        if (url.length() > MAX_URL_LENGTH) {
            throw validation(field + " must contain at most " + MAX_URL_LENGTH + " characters");
        }
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw validation(field + " must be an absolute HTTP(S) URL without credentials, query or fragment");
            }
            return url;
        } catch (URISyntaxException exception) {
            throw validation(field + " must be a valid absolute HTTP(S) URL");
        }
    }

    private static String optionalHttpUrl(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requireHttpUrl(value, field);
    }

    private static void validateSecretUpdate(String field, Object value, boolean clear) {
        if (clear && value != null) {
            throw validation(field + " and clear" + Character.toUpperCase(field.charAt(0))
                    + field.substring(1) + " cannot be submitted together");
        }
    }

    private static void validateSecretValue(String value, String field) {
        if (value == null) {
            return;
        }
        if (value.isBlank()) {
            throw validation(field + " must not be blank when supplied");
        }
        if (value.length() > MAX_SECRET_LENGTH) {
            throw validation(field + " is too long");
        }
    }

    private static Map<String, String> validateHeaders(Map<String, String> headers) {
        if (headers == null) {
            return null;
        }
        if (headers.size() > MAX_HEADER_COUNT) {
            throw validation("headers must contain at most " + MAX_HEADER_COUNT + " entries");
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (name == null || name.length() > MAX_HEADER_NAME_LENGTH || !HEADER_NAME.matcher(name).matches()) {
                throw validation("header names must use valid HTTP token characters");
            }
            if (FORBIDDEN_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                throw validation("header '" + name + "' is managed by the HTTP client and cannot be configured");
            }
            if (value == null || value.length() > MAX_HEADER_VALUE_LENGTH
                    || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw validation("header values must be non-null, bounded and contain no line breaks");
            }
        }
        return Map.copyOf(headers);
    }

    private static JsonNode validatePublicConfig(JsonNode publicConfig) {
        if (publicConfig == null || !publicConfig.isObject()) {
            throw validation("publicConfig must be a JSON object");
        }
        try {
            if (OBJECT_MAPPER.writeValueAsBytes(publicConfig).length > MAX_PUBLIC_CONFIG_BYTES) {
                throw validation("publicConfig is too large");
            }
        } catch (JsonProcessingException exception) {
            throw validation("publicConfig cannot be serialized");
        }
        return publicConfig.deepCopy();
    }

    private static void validateModelCatalog(
            Set<ModelCapability> capabilities,
            List<EnabledModel> enabledModels) {
        Objects.requireNonNull(capabilities, "capabilities must not be null");
        Objects.requireNonNull(enabledModels, "enabledModels must not be null");
        if (enabledModels.size() > MAX_ENABLED_MODELS) {
            throw validation("enabledModels must contain at most " + MAX_ENABLED_MODELS + " entries");
        }
        Set<String> uniqueModels = new HashSet<>();
        for (EnabledModel model : enabledModels) {
            if (!capabilities.contains(model.capability())) {
                throw validation("enabled model capability " + model.capability()
                        + " must be declared in capabilities");
            }
            String key = model.capability().name() + '\u0000' + model.name();
            if (!uniqueModels.add(key)) {
                throw validation("enabledModels contains a duplicate model entry");
            }
        }
    }

    private static BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message);
    }

    private static BusinessException notFound() {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "The requested resource does not exist.");
    }
}
