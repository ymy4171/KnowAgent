package com.knowagent.model.infrastructure.embedding;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.model.crypto.EncryptedSecret;
import com.knowagent.model.crypto.SecretCipher;
import com.knowagent.model.embedding.EmbeddingRequest;
import com.knowagent.model.embedding.EmbeddingResult;
import com.knowagent.model.infrastructure.embedding.config.EmbeddingProperties;
import com.knowagent.model.provider.EnabledModel;
import com.knowagent.model.provider.ModelCapability;
import com.knowagent.model.provider.ModelProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLHandshakeException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * WireMock contract tests for the OpenAI-compatible embedding adapter: normal single
 * and multi-batch calls, order preservation, 429 retry, 401 no-retry, timeout, wrong
 * response count, dimension mismatch, NaN/Infinity, cache invalidation on a
 * {@code configVersion} bump, cross-tenant rejection, and non-disclosure of the API
 * key, custom headers and chunk text in exceptions and logs.
 */
class OpenAiCompatibleEmbeddingGatewayTest {

    private static final TenantId TENANT_A = TenantId.of(UUID.randomUUID());
    private static final TenantId TENANT_B = TenantId.of(UUID.randomUUID());
    private static final String MODEL = "text-embedding-3-small";
    private static final String EMBEDDINGS_PATH = "/v1/embeddings";

    private final SecretCipher cipher = EmbeddingTestSupport.cipher();
    private final EmbeddingTestSupport.FakeRepository repository = new EmbeddingTestSupport.FakeRepository();
    private WireMockServer server;

    @BeforeEach
    void startWireMock() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
    }

    @AfterEach
    void stopWireMock() {
        if (server != null && server.isRunning()) {
            server.stop();
        }
    }

    @Test
    void embedsASingleBatchInOrder() {
        stubOk(server, vectors(new float[][]{{1f, 2f}, {3f, 4f}}));
        OpenAiCompatibleEmbeddingGateway gateway = gateway(maxAttempts(3));

        EmbeddingResult result = gateway.embed(request(List.of("alpha", "beta"), null));

        assertThat(result.dimensions()).isEqualTo(2);
        assertThat(result.model()).isEqualTo(MODEL);
        assertThat(result.batchCount()).isEqualTo(1);
        assertThat(result.vectors()).hasSize(2);
        assertThat(result.vectors().get(0)).containsExactly(1f, 2f);
        assertThat(result.vectors().get(1)).containsExactly(3f, 4f);
        assertThat(requestsTo(server)).isEqualTo(1);
    }

    @Test
    void embedsMultipleBatchesAndConcatenatesInOrder() {
        // maxTextsPerBatch=2 with four 1-token texts -> exactly two batches of two;
        // the first batch receives vectors [0,1] and the second [2,3].
        server.stubFor(post(urlEqualTo(EMBEDDINGS_PATH))
                .inScenario("batches")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(okJson(MODEL, new float[][]{{0f}, {1f}})))
                .willSetStateTo("second"));
        server.stubFor(post(urlEqualTo(EMBEDDINGS_PATH))
                .inScenario("batches")
                .whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(okJson(MODEL, new float[][]{{2f}, {3f}}))));
        OpenAiCompatibleEmbeddingGateway gateway = gateway(maxAttempts(3));

        EmbeddingResult result = gateway.embed(request(List.of("aaaa", "bbbb", "cccc", "dddd"), null));

        assertThat(requestsTo(server)).isEqualTo(2);
        assertThat(result.batchCount()).isEqualTo(2);
        assertThat(result.estimatedTokens()).isEqualTo(4);
        assertThat(result.vectors()).hasSize(4);
        assertThat(result.vectors().get(0)).containsExactly(0f);
        assertThat(result.vectors().get(1)).containsExactly(1f);
        assertThat(result.vectors().get(2)).containsExactly(2f);
        assertThat(result.vectors().get(3)).containsExactly(3f);
    }

    @Test
    void rejectsOutOfOrderIndexes() {
        stubOk(server, embeddingJson(MODEL, new float[][]{{1f}, {2f}}, true));
        OpenAiCompatibleEmbeddingGateway gateway = gateway(maxAttempts(3));

        assertThrowsError(() -> gateway.embed(request(List.of("a", "b"), null)), ErrorCode.MODEL_BAD_RESPONSE);
    }

    @Test
    void retriesAfterRateLimitsThenSucceeds() {
        server.stubFor(post(urlEqualTo(EMBEDDINGS_PATH))
                .inScenario("retry")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(429).withBody("{}"))
                .willSetStateTo("second"));
        server.stubFor(post(urlEqualTo(EMBEDDINGS_PATH))
                .inScenario("retry")
                .whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(429).withBody("{}"))
                .willSetStateTo("ok"));
        server.stubFor(post(urlEqualTo(EMBEDDINGS_PATH))
                .inScenario("retry")
                .whenScenarioStateIs("ok")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(okJson(MODEL, new float[][]{{1f}, {2f}}))));
        OpenAiCompatibleEmbeddingGateway gateway = gateway(maxAttempts(3));

        EmbeddingResult result = gateway.embed(request(List.of("a", "b"), null));

        assertThat(result.vectors()).hasSize(2);
        assertThat(requestsTo(server)).isEqualTo(3);
    }

    @Test
    void doesNotRetryAuthFailures() {
        server.stubFor(post(urlEqualTo(EMBEDDINGS_PATH))
                .willReturn(aResponse().withStatus(401).withBody("{\"error\":{\"message\":\"bad key\"}}")));
        OpenAiCompatibleEmbeddingGateway gateway = gateway(maxAttempts(3));

        BusinessException exception = assertThrowsError(
                () -> gateway.embed(request(List.of("a"), null)), ErrorCode.MODEL_AUTH_FAILED);

        assertThat(requestsTo(server)).isEqualTo(1);
        assertThat(exception.getMessage()).isEqualTo("Model provider returned HTTP status 401.");
    }

    @Test
    void exhaustsRetriesOnServerErrors() {
        server.stubFor(post(urlEqualTo(EMBEDDINGS_PATH))
                .willReturn(aResponse().withStatus(500).withBody("{\"error\":\"boom\"}")));
        OpenAiCompatibleEmbeddingGateway gateway = gateway(maxAttempts(3));

        assertThrowsError(() -> gateway.embed(request(List.of("a"), null)), ErrorCode.MODEL_SERVICE_ERROR);
        assertThat(requestsTo(server)).isEqualTo(3);
    }

    @Test
    void mapsReadTimeoutToModelTimeout() {
        server.stubFor(post(urlEqualTo(EMBEDDINGS_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(5_000)
                        .withBody(okJson(MODEL, new float[][]{{1f}}))));
        OpenAiCompatibleEmbeddingGateway gateway = gateway(new EmbeddingProperties(
                Duration.ofSeconds(5), Duration.ofSeconds(1), Duration.ofSeconds(10), 1,
                Duration.ofMillis(50), 2.0, Duration.ofMillis(200), 2, 8000, 200_000, 16));

        assertThrowsError(() -> gateway.embed(request(List.of("a"), null)), ErrorCode.MODEL_TIMEOUT);
    }

    @Test
    void totalTimeoutBoundsASuccessfulSlowCall() {
        server.stubFor(post(urlEqualTo(EMBEDDINGS_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(1_000)
                        .withBody(okJson(MODEL, new float[][]{{1f}}))));
        OpenAiCompatibleEmbeddingGateway gateway = gateway(new EmbeddingProperties(
                Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofMillis(100), 1,
                Duration.ZERO, 2.0, Duration.ofMillis(200), 2, 8000, 200_000, 16));

        long startedAt = System.nanoTime();
        assertThrowsError(() -> gateway.embed(request(List.of("a"), null)), ErrorCode.MODEL_TIMEOUT);

        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void totalTimeoutIsSharedAcrossBatches() {
        server.stubFor(post(urlEqualTo(EMBEDDINGS_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(175)
                        .withBody(okJson(MODEL, new float[][]{{1f}}))));
        OpenAiCompatibleEmbeddingGateway gateway = gateway(new EmbeddingProperties(
                Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofMillis(250), 1,
                Duration.ZERO, 2.0, Duration.ofMillis(200), 1, 8000, 200_000, 16));

        assertThrowsError(() -> gateway.embed(request(List.of("a", "b"), null)), ErrorCode.MODEL_TIMEOUT);
    }

    @Test
    void retriesOnlyExplicitTransientTransportFailures() {
        assertThat(OpenAiCompatibleEmbeddingGateway.isTransientTransportFailure(
                new org.springframework.web.client.ResourceAccessException(
                        "connection reset", new SocketException("reset")))).isTrue();
        assertThat(OpenAiCompatibleEmbeddingGateway.isTransientTransportFailure(
                new org.springframework.web.client.ResourceAccessException(
                        "bad certificate", new SSLHandshakeException("certificate")))).isFalse();
        assertThat(OpenAiCompatibleEmbeddingGateway.isTransientTransportFailure(
                new org.springframework.web.client.ResourceAccessException(
                        "bad provider host", new UnknownHostException("unknown host")))).isFalse();
    }

    @Test
    void rejectsWrongResponseCount() {
        stubOk(server, vectors(new float[][]{{1f}, {2f}, {3f}})); // 3 vectors for 2 texts
        OpenAiCompatibleEmbeddingGateway gateway = gateway(maxAttempts(3));

        assertThrowsError(() -> gateway.embed(request(List.of("a", "b"), null)), ErrorCode.MODEL_BAD_RESPONSE);
    }

    @Test
    void rejectsDimensionMismatchAgainstExpectedDimensions() {
        stubOk(server, vectors(new float[][]{{1f, 2f}, {3f, 4f}})); // dimension 2
        OpenAiCompatibleEmbeddingGateway gateway = gateway(maxAttempts(3));

        assertThrowsError(() -> gateway.embed(request(List.of("a", "b"), 1536)), ErrorCode.MODEL_BAD_RESPONSE);
    }

    @Test
    void sendsExpectedDimensionsToTheProviderOnlyWhenRequested() {
        stubOk(server, vectors(new float[][]{{1f, 2f}, {3f, 4f}}));
        OpenAiCompatibleEmbeddingGateway gateway = gateway(maxAttempts(3));

        gateway.embed(request(List.of("a", "b"), 2));
        gateway.embed(request(List.of("c", "d"), null));

        assertThat(anyRequestBodyContains(server, "\"dimensions\":2")).isTrue();
        assertThat(anyRequestBodyContains(server, "\"dimensions\":null")).isFalse();
    }

    @Test
    void rejectsInfiniteVectorValues() {
        // 1e400 is a valid JSON number that overflows to Float.POSITIVE_INFINITY.
        server.stubFor(post(urlEqualTo(EMBEDDINGS_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0,"
                                + "\"embedding\":[1e400]}],\"model\":\"" + MODEL
                                + "\",\"usage\":{\"prompt_tokens\":0,\"total_tokens\":0}}")));
        OpenAiCompatibleEmbeddingGateway gateway = gateway(maxAttempts(3));

        assertThrowsError(() -> gateway.embed(request(List.of("a"), null)), ErrorCode.MODEL_BAD_RESPONSE);
    }

    @Test
    void rejectsNaNVectorValues() {
        // NaN is not a JSON number; Spring AI cannot deserialize the body either.
        server.stubFor(post(urlEqualTo(EMBEDDINGS_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0,"
                                + "\"embedding\":[NaN]}],\"model\":\"" + MODEL
                                + "\",\"usage\":{\"prompt_tokens\":0,\"total_tokens\":0}}")));
        OpenAiCompatibleEmbeddingGateway gateway = gateway(maxAttempts(3));

        assertThrowsError(() -> gateway.embed(request(List.of("a"), null)), ErrorCode.MODEL_BAD_RESPONSE);
    }

    @Test
    void rejectsEmptyInputWithoutCallingTheProvider() {
        OpenAiCompatibleEmbeddingGateway gateway = gateway(maxAttempts(3));

        assertThrowsError(() -> gateway.embed(request(List.of(), null)), ErrorCode.VALIDATION_ERROR);
        assertThat(requestsTo(server)).isZero();
    }

    @Test
    void rejectsProvidersThatCannotServeTheRequest() {
        OpenAiCompatibleEmbeddingGateway gateway = gateway(maxAttempts(3));

        ModelProvider base = repositoryProvider();
        repository.setProvider(new ModelProvider(base.id(), base.tenantId(), base.providerKey(), base.displayName(),
                base.adapterType(), base.baseUrl(), null, null, base.secret(), base.headers(), base.capabilities(),
                base.enabledModels(), base.publicConfig(), false, base.healthStatus(), 1, base.createdBy(),
                base.updatedBy(), base.version(), base.createdAt(), base.updatedAt(), null));
        assertThrowsError(() -> gateway.embed(request(List.of("a"), null)), ErrorCode.MODEL_CONFIGURATION_ERROR);

        repository.setProvider(EmbeddingTestSupport.provider(TENANT_A, server.baseUrl(), MODEL, 1,
                null, null, Set.of(ModelCapability.CHAT), List.of()));
        assertThrowsError(() -> gateway.embed(request(List.of("a"), null)), ErrorCode.MODEL_CONFIGURATION_ERROR);

        repository.setProvider(EmbeddingTestSupport.provider(TENANT_A, server.baseUrl(), MODEL, 1,
                null, null, Set.of(ModelCapability.EMBEDDING), List.of(enabled("other-model"))));
        assertThrowsError(() -> gateway.embed(request(List.of("a"), null)), ErrorCode.MODEL_CONFIGURATION_ERROR);

        assertThat(requestsTo(server)).isZero();
    }

    @Test
    void allowsAnyModelWhenTheEnabledModelCatalogIsEmpty() {
        stubOk(server, vectors(new float[][]{{1f}}));
        repository.setProvider(EmbeddingTestSupport.provider(TENANT_A, server.baseUrl(), MODEL, 1,
                null, null, Set.of(ModelCapability.EMBEDDING), List.of()));
        OpenAiCompatibleEmbeddingGateway gateway = new OpenAiCompatibleEmbeddingGateway(
                repository, cipher, maxAttempts(3), null);

        EmbeddingResult result = gateway.embed(request(List.of("a"), null));

        assertThat(result.vectors()).hasSize(1);
    }

    @Test
    void rejectsACrossTenantProviderWithoutCallingIt() {
        repository.setProvider(EmbeddingTestSupport.provider(TENANT_A, server.baseUrl(), MODEL, 1,
                null, null, Set.of(ModelCapability.EMBEDDING), List.of(enabled(MODEL))));
        OpenAiCompatibleEmbeddingGateway gateway = new OpenAiCompatibleEmbeddingGateway(
                repository, cipher, maxAttempts(3), null);

        assertThrowsError(() -> gateway.embed(
                new EmbeddingRequest(TENANT_B, repositoryProviderId(), MODEL, null, List.of("a"))),
                ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(requestsTo(server)).isZero();
    }

    @Test
    void configVersionChangeBuildsANewClient() {
        WireMockServer first = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        WireMockServer second = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        try {
            first.start();
            second.start();
            stubOk(first, vectors(new float[][]{{1f}}));
            stubOk(second, vectors(new float[][]{{2f}}));
            ModelProvider providerV1 = EmbeddingTestSupport.provider(TENANT_A, first.baseUrl(), MODEL, 1,
                    null, null, Set.of(ModelCapability.EMBEDDING), List.of(enabled(MODEL)));
            repository.setProvider(providerV1);
            OpenAiCompatibleEmbeddingGateway gateway = new OpenAiCompatibleEmbeddingGateway(
                    repository, cipher, maxAttempts(3), null);

            EmbeddingResult v1 = gateway.embed(request(List.of("a"), null));
            assertThat(v1.vectors().get(0)).containsExactly(1f);
            assertThat(requestsTo(first)).isEqualTo(1);

            repository.setProvider(new ModelProvider(providerV1.id(), providerV1.tenantId(), providerV1.providerKey(),
                    providerV1.displayName(), providerV1.adapterType(), second.baseUrl(), null, null,
                    providerV1.secret(), providerV1.headers(), providerV1.capabilities(), providerV1.enabledModels(),
                    providerV1.publicConfig(), true, providerV1.healthStatus(), 2, providerV1.createdBy(),
                    providerV1.updatedBy(), providerV1.version(), providerV1.createdAt(), providerV1.updatedAt(), null));

            EmbeddingResult v2 = gateway.embed(request(List.of("b"), null));
            assertThat(v2.vectors().get(0)).containsExactly(2f);
            assertThat(requestsTo(first)).isEqualTo(1); // old client not reused
            assertThat(requestsTo(second)).isEqualTo(1);
        } finally {
            if (first.isRunning()) {
                first.stop();
            }
            if (second.isRunning()) {
                second.stop();
            }
        }
    }

    @Test
    void exceptionsNeverLeakSecretsHeadersOrChunkText() {
        EncryptedSecret secret = cipher.encrypt("sk-super-secret-123");
        EncryptedSecret headers = cipher.encrypt("{\"X-Provider-Auth\":\"header-secret-xyz\"}");
        repository.setProvider(EmbeddingTestSupport.provider(TENANT_A, server.baseUrl(), MODEL, 1,
                secret, headers, Set.of(ModelCapability.EMBEDDING), List.of(enabled(MODEL))));
        server.stubFor(post(urlEqualTo(EMBEDDINGS_PATH))
                .willReturn(aResponse().withStatus(401)
                        .withBody("{\"error\":\"invalid api key: sk-super-secret-123\"}")));
        OpenAiCompatibleEmbeddingGateway gateway = new OpenAiCompatibleEmbeddingGateway(
                repository, cipher, maxAttempts(3), null);

        BusinessException exception = assertThrowsError(
                () -> gateway.embed(request(List.of("ORIGINAL-CHUNK-TEXT-SENTINEL"), null)),
                ErrorCode.MODEL_AUTH_FAILED);

        assertThat(exception.getMessage())
                .doesNotContain("sk-super-secret-123", "header-secret-xyz", "ORIGINAL-CHUNK-TEXT-SENTINEL");
        // The decrypted secret and custom header were still used at the HTTP boundary.
        server.verify(postRequestedFor(urlEqualTo(EMBEDDINGS_PATH))
                .withHeader("Authorization", equalTo("Bearer sk-super-secret-123"))
                .withHeader("X-Provider-Auth", equalTo("header-secret-xyz")));
    }

    @Test
    void recordsNonSensitiveMetrics() {
        stubOk(server, vectors(new float[][]{{1f}}));
        repository.setProvider(EmbeddingTestSupport.provider(TENANT_A, server.baseUrl(), MODEL, 1,
                null, null, Set.of(ModelCapability.EMBEDDING), List.of(enabled(MODEL))));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        OpenAiCompatibleEmbeddingGateway gateway = new OpenAiCompatibleEmbeddingGateway(
                repository, cipher, maxAttempts(3), meterRegistry);

        gateway.embed(request(List.of("aaaa"), null));

        var timer = meterRegistry.get("knowagent.model.embedding.calls").timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.getId().getTag("outcome")).isEqualTo("success");
        assertThat(timer.getId().getTag("provider")).isEqualTo(repositoryProviderId().toString());
        assertThat(timer.getId().getTag("model")).isEqualTo(MODEL);
    }

    // ---- helpers ----

    private ModelProvider repositoryProvider() {
        return repository.findActiveByKey(TENANT_A, "openai").orElseThrow();
    }

    private UUID repositoryProviderId() {
        return repositoryProvider().id();
    }

    /** Seeds a default tenant-A provider pointing at the current WireMock server. */
    private OpenAiCompatibleEmbeddingGateway gateway(EmbeddingProperties properties) {
        repository.setProvider(EmbeddingTestSupport.provider(TENANT_A, server.baseUrl(), MODEL, 1,
                null, null, Set.of(ModelCapability.EMBEDDING), List.of(enabled(MODEL))));
        return new OpenAiCompatibleEmbeddingGateway(repository, cipher, properties, null);
    }

    private static EmbeddingProperties maxAttempts(int attempts) {
        return new EmbeddingProperties(Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(30), attempts,
                Duration.ofMillis(50), 2.0, Duration.ofMillis(200), 2, 8000, 200_000, 16);
    }

    private EmbeddingRequest request(List<String> texts, Integer dimensions) {
        return new EmbeddingRequest(TENANT_A, repositoryProviderId(), MODEL, dimensions, texts);
    }

    private static EnabledModel enabled(String model) {
        return new EnabledModel(model, ModelCapability.EMBEDDING);
    }

    private static void stubOk(WireMockServer target, String json) {
        target.stubFor(post(urlEqualTo(EMBEDDINGS_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(json)));
    }

    private static String vectors(float[][] vectors) {
        return okJson(MODEL, vectors);
    }

    private static String okJson(String model, float[][] vectors) {
        return embeddingJson(model, vectors, false);
    }

    private static String embeddingJson(String model, float[][] vectors, boolean reversedIndexes) {
        StringBuilder json = new StringBuilder("{\"object\":\"list\",\"data\":[");
        for (int i = 0; i < vectors.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            int index = reversedIndexes ? vectors.length - 1 - i : i;
            float[] vector = vectors[i];
            json.append("{\"object\":\"embedding\",\"index\":").append(index).append(",\"embedding\":[");
            for (int j = 0; j < vector.length; j++) {
                if (j > 0) {
                    json.append(',');
                }
                json.append(vector[j]);
            }
            json.append("]}");
        }
        return json.append("],\"model\":\"").append(model)
                .append("\",\"usage\":{\"prompt_tokens\":0,\"total_tokens\":0}}").toString();
    }

    private static long requestsTo(WireMockServer target) {
        return target.getAllServeEvents().stream()
                .filter(event -> EMBEDDINGS_PATH.equals(event.getRequest().getUrl()))
                .count();
    }

    private static boolean anyRequestBodyContains(WireMockServer target, String fragment) {
        return target.getAllServeEvents().stream()
                .anyMatch(event -> new String(event.getRequest().getBody(), StandardCharsets.UTF_8).contains(fragment));
    }

    private static BusinessException assertThrowsError(Runnable call, ErrorCode code) {
        BusinessException thrown = catchThrowableOfType(call::run, BusinessException.class);
        assertThat(thrown).isNotNull();
        assertThat(thrown.errorCode()).isEqualTo(code);
        return thrown;
    }
}
