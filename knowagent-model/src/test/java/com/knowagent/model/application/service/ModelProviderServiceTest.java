package com.knowagent.model.application.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.dao.DuplicateKeyException;
import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.model.application.port.out.ModelProviderReferenceChecker;
import com.knowagent.model.application.port.out.ModelProviderRepository;
import com.knowagent.model.crypto.AesGcmSecretCipher;
import com.knowagent.model.crypto.SecretCipher;
import com.knowagent.model.provider.AdapterType;
import com.knowagent.model.provider.EnabledModel;
import com.knowagent.model.provider.ModelCapability;
import com.knowagent.model.provider.ModelProvider;
import com.knowagent.model.provider.ModelProviderPage;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelProviderServiceTest {

    private static final TenantId TENANT_A = TenantId.of(UUID.randomUUID());
    private static final TenantId TENANT_B = TenantId.of(UUID.randomUUID());
    private static final UUID ACTOR = UUID.randomUUID();

    private final FakeRepository repository = new FakeRepository();
    private final FakeReferenceChecker referenceChecker = new FakeReferenceChecker();
    private final SecretCipher cipher = new AesGcmSecretCipher(
            Map.of(1, new SecretKeySpec("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8), "AES")), 1);
    private final ModelProviderService service = new ModelProviderService(repository, referenceChecker, cipher);

    @Test
    void createNormalizesProviderKeyToLowercase() {
        ModelProvider created = service.create(createCommand(b -> b.providerKey("OpenAI-Main")));
        assertThat(created.providerKey()).isEqualTo("openai-main");
    }

    @Test
    void createRejectsAnInvalidProviderKey() {
        assertThatThrownBy(() -> service.create(createCommand(b -> b.providerKey("OpenAI Main!"))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void createEncryptsTheSecretSoPlaintextIsNeverStored() {
        ModelProvider created = service.create(createCommand(b -> b.secret("sk-live-plaintext")));

        assertThat(created.hasSecret()).isTrue();
        assertThat(created.secret().envelope()).doesNotContain("sk-live-plaintext");
        assertThat(created.secret().keyVersion()).isEqualTo(1);
    }

    @Test
    void createDuplicateKeyReturnsConflict() {
        service.create(createCommand(b -> b.providerKey("openai")));

        assertThatThrownBy(() -> service.create(createCommand(b -> b.providerKey("OPENAI"))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void createWithSecretButNoMasterKeyIsRejected() {
        ModelProviderService noKey = new ModelProviderService(
                repository, referenceChecker, new AesGcmSecretCipher(Map.of(), 1));

        assertThatThrownBy(() -> noKey.create(createCommand(b -> b.secret("sk-secret"))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
    }

    @Test
    void createRejectsInvalidEndpointAndNonObjectPublicConfig() {
        assertValidationError(() -> service.create(createCommand(b -> b.baseUrl("not-a-url"))));
        assertValidationError(() -> service.create(createCommand(
                b -> b.publicConfig(JsonNodeFactory.instance.arrayNode()))));
    }

    @Test
    void createRejectsBlankSecretAndInconsistentModelCatalog() {
        assertValidationError(() -> service.create(createCommand(b -> b.secret("  "))));
        assertValidationError(() -> service.create(createCommand(b -> {
            b.capabilities(Set.of(ModelCapability.CHAT));
            b.enabledModels(List.of(new EnabledModel("embedding-model", ModelCapability.EMBEDDING)));
        })));
    }

    @Test
    void updateWithoutSecretKeepsTheStoredSecret() {
        ModelProvider created = service.create(createCommand(b -> b.secret("sk-original")));
        String before = created.secret().envelope();

        ModelProvider updated = service.update(new UpdateModelProviderCommand(
                TENANT_A, created.id(), null, "Renamed", null, null, null, null, null, null, null, null,
                null, null, false, false, ACTOR));

        assertThat(updated.displayName()).isEqualTo("Renamed");
        assertThat(updated.secret().envelope()).isEqualTo(before);
    }

    @Test
    void updateClearSecretClearsTheSecret() {
        ModelProvider created = service.create(createCommand(b -> b.secret("sk-original")));

        ModelProvider updated = service.update(new UpdateModelProviderCommand(
                TENANT_A, created.id(), null, null, null, null, null, null, null, null, null, null,
                null, null, true, false, ACTOR));

        assertThat(updated.hasSecret()).isFalse();
        assertThat(updated.secret()).isNull();
    }

    @Test
    void updateWithNewSecretReplacesTheOldEnvelope() {
        ModelProvider created = service.create(createCommand(b -> b.secret("sk-old")));

        ModelProvider updated = service.update(new UpdateModelProviderCommand(
                TENANT_A, created.id(), null, null, null, null, null, null, null, null, null, null,
                "sk-new", null, false, false, ACTOR));

        assertThat(updated.secret().envelope()).doesNotContain("sk-new");
        assertThat(updated.secret().envelope()).isNotEqualTo(created.secret().envelope());
    }

    @Test
    void updateClearHeadersClearsTheEncryptedHeaders() {
        ModelProvider created = service.create(createCommand(
                b -> b.headers(Map.of("Authorization", "Bearer original"))));
        assertThat(created.headers()).isNotNull();

        ModelProvider updated = service.update(new UpdateModelProviderCommand(
                TENANT_A, created.id(), null, null, null, null, null, null, null, null, null, null,
                null, null, false, true, ACTOR));

        assertThat(updated.headers()).isNull();
    }

    @Test
    void updateRejectsValueAndClearFlagTogether() {
        ModelProvider created = service.create(createCommand(b -> b.secret("sk-original")));

        assertValidationError(() -> service.update(new UpdateModelProviderCommand(
                TENANT_A, created.id(), null, null, null, null, null, null, null, null, null, null,
                "sk-new", null, true, false, ACTOR)));
        assertValidationError(() -> service.update(new UpdateModelProviderCommand(
                TENANT_A, created.id(), null, null, null, null, null, null, null, null, null, null,
                null, Map.of("Authorization", "Bearer replacement"), false, true, ACTOR)));
    }

    @Test
    void updateUniqueConstraintRaceReturnsConflict() {
        ModelProvider created = service.create(createCommand(b -> b.providerKey("first")));
        repository.failNextUpdateWithDuplicate = true;

        assertThatThrownBy(() -> service.update(new UpdateModelProviderCommand(
                TENANT_A, created.id(), "shared", null, null, null, null, null, null, null, null, null,
                null, null, false, false, ACTOR)))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).errorCode())
                        .isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void deleteReferencedProviderReturnsConflict() {
        ModelProvider created = service.create(createCommand(b -> b.providerKey("referenced")));
        referenceChecker.referenced.add(created.id());

        assertThatThrownBy(() -> service.delete(TENANT_A, created.id()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void softDeleteAllowsTheProviderKeyToBeReused() {
        ModelProvider created = service.create(createCommand(b -> b.providerKey("reusable")));
        service.delete(TENANT_A, created.id());

        ModelProvider recreated = service.create(createCommand(b -> b.providerKey("reusable")));
        assertThat(recreated.id()).isNotEqualTo(created.id());
    }

    @Test
    void getAndListAreTenantScoped() {
        ModelProvider created = service.create(createCommand(b -> b.providerKey("alpha-only")));

        assertThatThrownBy(() -> service.get(TENANT_B, created.id()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        assertThat(service.list(TENANT_B, 1, 20).total()).isZero();
        assertThat(service.list(TENANT_A, 1, 20).total()).isEqualTo(1);
    }

    @Test
    void invalidPagingIsRejected() {
        assertValidationError(() -> service.list(TENANT_A, 0, 20));
        assertValidationError(() -> service.list(TENANT_A, 1, 0));
        assertValidationError(() -> service.list(TENANT_A, 1, 101));
    }

    private void assertValidationError(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    private static CreateModelProviderCommand createCommand(java.util.function.Consumer<CommandBuilder> customize) {
        CommandBuilder builder = new CommandBuilder();
        customize.accept(builder);
        return builder.build();
    }

    private static final class CommandBuilder {
        String providerKey = "default";
        String displayName = "Default";
        AdapterType adapterType = AdapterType.OPENAI_COMPATIBLE;
        String baseUrl = "https://api.example.com/v1";
        String secret;
        Map<String, String> headers;
        String embeddingBaseUrl;
        String rerankBaseUrl;
        Set<ModelCapability> capabilities = Set.of(ModelCapability.CHAT);
        List<EnabledModel> enabledModels = List.of(new EnabledModel("gpt-4o-mini", ModelCapability.CHAT));
        com.fasterxml.jackson.databind.JsonNode publicConfig = JsonNodeFactory.instance.objectNode();

        CommandBuilder providerKey(String v) { this.providerKey = v; return this; }
        CommandBuilder secret(String v) { this.secret = v; return this; }
        CommandBuilder headers(Map<String, String> v) { this.headers = v; return this; }
        CommandBuilder baseUrl(String v) { this.baseUrl = v; return this; }
        CommandBuilder capabilities(Set<ModelCapability> v) { this.capabilities = v; return this; }
        CommandBuilder enabledModels(List<EnabledModel> v) { this.enabledModels = v; return this; }
        CommandBuilder publicConfig(com.fasterxml.jackson.databind.JsonNode v) { this.publicConfig = v; return this; }

        CreateModelProviderCommand build() {
            return new CreateModelProviderCommand(
                    TENANT_A, providerKey, displayName, adapterType, baseUrl, embeddingBaseUrl, rerankBaseUrl,
                    capabilities, enabledModels, publicConfig, true, secret, headers, ACTOR);
        }
    }

    private static final class FakeRepository implements ModelProviderRepository {
        final Map<UUID, ModelProvider> byId = new HashMap<>();
        boolean failNextUpdateWithDuplicate;

        @Override public void save(ModelProvider provider) { byId.put(provider.id(), provider); }

        @Override public Optional<ModelProvider> findById(TenantId tenantId, UUID id) {
            ModelProvider provider = byId.get(id);
            return provider != null && provider.tenantId().equals(tenantId) ? Optional.of(provider) : Optional.empty();
        }

        @Override public Optional<ModelProvider> findByIdForUpdate(TenantId tenantId, UUID id) {
            return findById(tenantId, id);
        }

        @Override public Optional<ModelProvider> findByIdForKeyShare(TenantId tenantId, UUID id) {
            return findById(tenantId, id);
        }

        @Override public Optional<ModelProvider> findActiveByKey(TenantId tenantId, String providerKey) {
            return byId.values().stream()
                    .filter(p -> p.tenantId().equals(tenantId) && p.providerKey().equals(providerKey))
                    .findFirst();
        }

        @Override public ModelProviderPage page(TenantId tenantId, int page, int size) {
            List<ModelProvider> providers = byId.values().stream()
                    .filter(p -> p.tenantId().equals(tenantId))
                    .toList();
            return new ModelProviderPage(providers, providers.size());
        }

        @Override public int updateConfig(ModelProvider provider) {
            if (failNextUpdateWithDuplicate) {
                failNextUpdateWithDuplicate = false;
                throw new DuplicateKeyException("simulated unique-key race");
            }
            ModelProvider existing = byId.get(provider.id());
            if (existing == null || existing.version() != provider.version()) {
                return 0;
            }
            byId.put(provider.id(), provider);
            return 1;
        }

        @Override public int softDelete(TenantId tenantId, UUID id, long version) {
            ModelProvider existing = byId.get(id);
            if (existing == null || existing.version() != version) {
                return 0;
            }
            byId.remove(id);
            return 1;
        }
    }

    private static final class FakeReferenceChecker implements ModelProviderReferenceChecker {
        final Set<UUID> referenced = new java.util.HashSet<>();

        @Override public boolean isReferencedByActiveKnowledgeBase(TenantId tenantId, UUID providerId) {
            return referenced.contains(providerId);
        }
    }
}
