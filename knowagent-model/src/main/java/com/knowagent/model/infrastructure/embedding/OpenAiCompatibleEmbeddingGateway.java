package com.knowagent.model.infrastructure.embedding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.model.application.port.out.ModelProviderRepository;
import com.knowagent.model.crypto.EncryptedSecret;
import com.knowagent.model.crypto.SecretCipher;
import com.knowagent.model.crypto.SecretCipherException;
import com.knowagent.model.embedding.BatchPlanner;
import com.knowagent.model.embedding.CharRunTokenEstimator;
import com.knowagent.model.embedding.EmbeddingGateway;
import com.knowagent.model.embedding.EmbeddingRequest;
import com.knowagent.model.embedding.EmbeddingResult;
import com.knowagent.model.infrastructure.embedding.EmbeddingModelClientCache.EmbeddingClientKey;
import com.knowagent.model.infrastructure.embedding.config.EmbeddingProperties;
import com.knowagent.model.provider.AdapterType;
import com.knowagent.model.provider.ModelCapability;
import com.knowagent.model.provider.ModelProvider;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.model.ApiKey;
import org.springframework.ai.model.NoopApiKey;
import org.springframework.ai.model.SimpleApiKey;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * OpenAI-compatible embedding adapter implementing {@link EmbeddingGateway}.
 *
 * <p>Per call it resolves the tenant's {@link ModelProvider} (never another tenant's),
 * batches the input with {@link BatchPlanner} under the configured text / token /
 * request-body limits, and drives an {@link OpenAiEmbeddingModel} built from the
 * provider configuration. The model client is cached under
 * {@code (tenantId, providerId, configVersion)} so a configuration update invalidates
 * the old client; the decrypted API key and custom headers exist only inside the built
 * client's request headers (the HTTP-call boundary) and never enter a log, exception,
 * cache key or business object.
 *
 * <p>Spring AI supplies only the protocol: {@code OpenAiApi} + {@code OpenAiEmbeddingModel}.
 * The default Spring AI retry template (10 attempts, exponential backoff up to 3
 * minutes, no 429 handling) and its default error handler (which embeds the raw
 * provider body in exception messages) are both replaced: the adapter runs its own
 * bounded retry loop under a total-timeout deadline that retries 429, explicit 5xx and
 * transport-level timeouts, and maps every failure to a stable {@link ErrorCode}
 * without ever returning the provider's raw body. Vectors are validated for count,
 * order, non-emptiness, finiteness and dimension consistency.
 */
public class OpenAiCompatibleEmbeddingGateway implements EmbeddingGateway {

    private static final RetryTemplate NO_INTERNAL_RETRY = RetryTemplate.builder().maxAttempts(1).build();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ModelProviderRepository providers;
    private final SecretCipher secretCipher;
    private final EmbeddingProperties properties;
    private final EmbeddingModelClientCache clientCache;
    private final EmbeddingMetrics metrics;
    private final BatchPlanner.TokenEstimator tokenEstimator;

    public OpenAiCompatibleEmbeddingGateway(ModelProviderRepository providers,
                                            SecretCipher secretCipher,
                                            EmbeddingProperties properties,
                                            io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.providers = Objects.requireNonNull(providers, "providers must not be null");
        this.secretCipher = Objects.requireNonNull(secretCipher, "secretCipher must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clientCache = new EmbeddingModelClientCache(properties.maxClientCacheSize());
        this.metrics = new EmbeddingMetrics(meterRegistry);
        this.tokenEstimator = CharRunTokenEstimator.INSTANCE;
    }

    @Override
    public EmbeddingResult embed(EmbeddingRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.texts().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Cannot embed an empty text list.");
        }
        long startedAt = System.nanoTime();
        long deadline = startedAt + properties.totalTimeout().toNanos();
        ModelProvider provider = resolveProvider(request);
        try {
            BatchPlanner.Limits limits = new BatchPlanner.Limits(
                    properties.maxTextsPerBatch(), properties.maxTokensPerBatch(),
                    properties.maxRequestBodyBytes(),
                    BatchPlanner.estimateJsonStringBytes(request.model()), tokenEstimator);
            List<BatchPlanner.Batch> batches = BatchPlanner.plan(request.texts(), limits);
            OpenAiEmbeddingModel model = client(request, provider);

            List<float[]> vectors = new ArrayList<>(request.texts().size());
            int dimensions = request.expectedDimensions() == null ? -1 : request.expectedDimensions();
            long estimatedTokens = 0;
            for (BatchPlanner.Batch batch : batches) {
                estimatedTokens += batch.estimatedTokens();
                float[][] batchVectors = callBatch(model, batch, request, deadline);
                for (float[] vector : batchVectors) {
                    if (dimensions == -1) {
                        dimensions = vector.length;
                    } else if (vector.length != dimensions) {
                        throw new BusinessException(ErrorCode.MODEL_BAD_RESPONSE,
                                "The embedding provider returned vectors with inconsistent dimensions.");
                    }
                    vectors.add(vector);
                }
            }
            if (vectors.size() != request.texts().size()) {
                throw new BusinessException(ErrorCode.MODEL_BAD_RESPONSE,
                        "The embedding provider returned an unexpected number of vectors.");
            }
            metrics.recordCall(provider.id(), request.model(), EmbeddingMetrics.Outcome.SUCCESS,
                    System.nanoTime() - startedAt, batches.size(), estimatedTokens);
            return new EmbeddingResult(List.copyOf(vectors), dimensions, request.model(),
                    batches.size(), estimatedTokens);
        } catch (BusinessException exception) {
            metrics.recordCall(provider.id(), request.model(), outcomeFor(exception.errorCode()),
                    System.nanoTime() - startedAt, 0, 0);
            throw exception;
        }
    }

    /** Resolves the tenant-scoped provider and validates it can serve the embedding model. */
    private ModelProvider resolveProvider(EmbeddingRequest request) {
        ModelProvider provider = providers.findById(request.tenantId(), request.providerId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "The requested model provider does not exist."));
        if (provider.adapterType() != AdapterType.OPENAI_COMPATIBLE) {
            throw new BusinessException(ErrorCode.MODEL_CONFIGURATION_ERROR,
                    "The model provider does not support the OpenAI-compatible embedding protocol.");
        }
        if (!provider.enabled()) {
            throw new BusinessException(ErrorCode.MODEL_CONFIGURATION_ERROR,
                    "The model provider is disabled.");
        }
        if (!provider.capabilities().contains(ModelCapability.EMBEDDING)) {
            throw new BusinessException(ErrorCode.MODEL_CONFIGURATION_ERROR,
                    "The model provider does not declare the embedding capability.");
        }
        // An empty enabled_models catalog means the tenant does not restrict models.
        if (!provider.enabledModels().isEmpty()
                && provider.enabledModels().stream()
                        .noneMatch(model -> model.capability() == ModelCapability.EMBEDDING
                                && model.name().equals(request.model()))) {
            throw new BusinessException(ErrorCode.MODEL_CONFIGURATION_ERROR,
                    "The requested embedding model is not enabled on the provider.");
        }
        return provider;
    }

    private OpenAiEmbeddingModel client(EmbeddingRequest request, ModelProvider provider) {
        EmbeddingClientKey key = new EmbeddingClientKey(request.tenantId(), provider.id(), provider.configVersion());
        return clientCache.getOrCreate(key, ignored -> buildModel(provider, request));
    }

    /** Builds a fresh client from the provider config. Runs only on a cache miss. */
    private OpenAiEmbeddingModel buildModel(ModelProvider provider, EmbeddingRequest request) {
        String baseUrl = provider.embeddingBaseUrl() != null ? provider.embeddingBaseUrl() : provider.baseUrl();
        ApiKey apiKey = provider.secret() == null
                ? new NoopApiKey()
                : new SimpleApiKey(decrypt(provider.secret(), "secret"));
        MultiValueMap<String, String> customHeaders = new LinkedMultiValueMap<>();
        if (provider.headers() != null) {
            decodeHeaders(decrypt(provider.headers(), "headers"), customHeaders);
        }

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(shorter(properties.connectTimeout(), properties.totalTimeout()));
        requestFactory.setReadTimeout(shorter(properties.readTimeout(), properties.totalTimeout()));
        RestClient.Builder restClient = RestClient.builder().requestFactory(requestFactory);

        OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(restClient)
                .responseErrorHandler(new OpenAiResponseErrorHandler());
        if (!customHeaders.isEmpty()) {
            apiBuilder.headers(customHeaders);
        }
        OpenAiApi api = apiBuilder.build();
        // The default options are neutral; the model and optional dimensions are passed
        // as request-level options on every call so one cached client can serve several
        // knowledge bases that use different embedding models on the same provider.
        return new OpenAiEmbeddingModel(api, MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder().build(), NO_INTERNAL_RETRY);
    }

    /** Decrypts a stored secret only at client-build time (the HTTP-call boundary). */
    private String decrypt(EncryptedSecret secret, String field) {
        try {
            return secretCipher.decrypt(secret);
        } catch (SecretCipherException exception) {
            throw new BusinessException(ErrorCode.MODEL_CONFIGURATION_ERROR,
                    "The provider's stored " + field + " cannot be decrypted.");
        }
    }

    private void decodeHeaders(String json, MultiValueMap<String, String> target) {
        try {
            Map<String, String> headers = OBJECT_MAPPER.readValue(json, new TypeReference<Map<String, String>>() {
            });
            headers.forEach(target::add);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.MODEL_CONFIGURATION_ERROR,
                    "The provider's stored custom headers cannot be decoded.");
        }
    }

    /** Calls one batch with bounded retries under a total-timeout deadline. */
    private float[][] callBatch(OpenAiEmbeddingModel model, BatchPlanner.Batch batch,
                                EmbeddingRequest request, long deadlineNanos) {
        org.springframework.ai.embedding.EmbeddingRequest aiRequest =
                new org.springframework.ai.embedding.EmbeddingRequest(batch.texts(), runtimeOptions(request));
        int maxAttempts = properties.maxAttempts();
        long backoff = properties.backoffInitial().toNanos();
        for (int attempt = 1; ; attempt++) {
            try {
                EmbeddingResponse response = callBeforeDeadline(model, aiRequest, deadlineNanos);
                return extractVectors(response, batch.texts().size(), request.expectedDimensions());
            } catch (RuntimeException error) {
                ModelCallFailure failure = classify(error);
                if (!failure.retryable() || attempt >= maxAttempts) {
                    throw failure.toBusinessException();
                }
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0 || backoff >= remaining) {
                    throw new BusinessException(ErrorCode.MODEL_TIMEOUT,
                            "Model provider call exceeded the total timeout.");
                }
                sleep(backoff);
                long nextBackoff = (long) (backoff * properties.backoffMultiplier());
                backoff = Math.min(nextBackoff, properties.backoffMax().toNanos());
            }
        }
    }

    /** Runs one synchronous Spring AI call within the remaining embed-wide budget. */
    private EmbeddingResponse callBeforeDeadline(OpenAiEmbeddingModel model,
                                                 org.springframework.ai.embedding.EmbeddingRequest request,
                                                 long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            throw new BusinessException(ErrorCode.MODEL_TIMEOUT,
                    "Model provider call exceeded the total timeout.");
        }

        FutureTask<EmbeddingResponse> task = new FutureTask<>(() -> model.call(request));
        Thread.ofVirtual().name("knowagent-embedding-call").start(task);
        try {
            return task.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            task.cancel(true);
            throw new BusinessException(ErrorCode.MODEL_TIMEOUT,
                    "Model provider call exceeded the total timeout.");
        } catch (InterruptedException exception) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.MODEL_TIMEOUT,
                    "Model provider call was interrupted.");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error fatalError) {
                throw fatalError;
            }
            throw new IllegalStateException("Unexpected checked exception from embedding call.", cause);
        }
    }

    private OpenAiEmbeddingOptions runtimeOptions(EmbeddingRequest request) {
        OpenAiEmbeddingOptions.Builder builder = OpenAiEmbeddingOptions.builder().model(request.model());
        if (request.expectedDimensions() != null) {
            builder.dimensions(request.expectedDimensions());
        }
        return builder.build();
    }

    /**
     * Validates a batch response: exact count, provider index matching position,
     * non-empty and finite vectors, and (when requested) the expected dimension.
     */
    private float[][] extractVectors(EmbeddingResponse response, int expectedCount, Integer expectedDimensions) {
        List<Embedding> results = response.getResults();
        if (results == null || results.size() != expectedCount) {
            throw new BusinessException(ErrorCode.MODEL_BAD_RESPONSE,
                    "The embedding provider returned an unexpected number of vectors.");
        }
        float[][] vectors = new float[expectedCount][];
        for (int i = 0; i < expectedCount; i++) {
            Embedding embedding = results.get(i);
            if (embedding.getIndex() != null && embedding.getIndex() != i) {
                throw new BusinessException(ErrorCode.MODEL_BAD_RESPONSE,
                        "The embedding provider returned vectors in an unexpected order.");
            }
            float[] output = embedding.getOutput();
            if (output == null || output.length == 0) {
                throw new BusinessException(ErrorCode.MODEL_BAD_RESPONSE,
                        "The embedding provider returned an empty vector.");
            }
            if (expectedDimensions != null && output.length != expectedDimensions) {
                throw new BusinessException(ErrorCode.MODEL_BAD_RESPONSE,
                        "The embedding provider returned a vector dimension that does not match the expected dimension.");
            }
            for (float value : output) {
                if (!Float.isFinite(value)) {
                    throw new BusinessException(ErrorCode.MODEL_BAD_RESPONSE,
                            "The embedding provider returned a non-finite vector value.");
                }
            }
            vectors[i] = output;
        }
        return vectors;
    }

    /**
     * Walks the cause chain (spring-retry can wrap the last failure) and maps it to a
     * retryable flag plus a stable error code. Provider response bodies are never read.
     */
    private ModelCallFailure classify(Throwable error) {
        Throwable current = error;
        boolean sawRestClientException = false;
        boolean sawResourceAccessException = false;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (current instanceof BusinessException business) {
                return new ModelCallFailure(false, business);
            }
            if (current instanceof ModelCallException modelCall) {
                return new ModelCallFailure(modelCall.retryable(), modelCall.toErrorCode(), modelCall.getMessage());
            }
            if (current instanceof ResourceAccessException resourceAccess) {
                sawResourceAccessException = true;
                if (isTimeoutFailure(resourceAccess)) {
                    return new ModelCallFailure(true, ErrorCode.MODEL_TIMEOUT,
                            "Model provider call timed out.");
                }
                if (isTransientTransportFailure(resourceAccess)) {
                    return new ModelCallFailure(true, ErrorCode.MODEL_SERVICE_ERROR,
                            "Model provider was temporarily unreachable.");
                }
            }
            if (current instanceof HttpStatusCodeException statusCode) {
                // Defensive: normally the custom error handler already maps these.
                int status = statusCode.getStatusCode().value();
                ModelCallException mapped = new ModelCallException(switch (status) {
                    case 401, 403 -> ModelCallException.Kind.AUTH;
                    case 429 -> ModelCallException.Kind.RATE_LIMITED;
                    default -> status >= 500 ? ModelCallException.Kind.TRANSIENT_SERVICE
                            : ModelCallException.Kind.CLIENT_CONFIG;
                }, status);
                return new ModelCallFailure(mapped.retryable(), mapped.toErrorCode(), mapped.getMessage());
            }
            if (current instanceof SocketTimeoutException || current instanceof ConnectException) {
                // A transport timeout may surface wrapped inside a body-conversion
                // failure (e.g. HttpMessageNotReadableException); prefer the timeout
                // classification over the generic unreadable-response one.
                return new ModelCallFailure(true, ErrorCode.MODEL_TIMEOUT, "Model provider call timed out.");
            }
            if (current instanceof RestClientException) {
                sawRestClientException = true;
            }
            current = current.getCause();
        }
        if (sawResourceAccessException) {
            return new ModelCallFailure(false, ErrorCode.MODEL_SERVICE_ERROR,
                    "Model provider transport configuration failed.");
        }
        if (sawRestClientException) {
            return new ModelCallFailure(false, ErrorCode.MODEL_BAD_RESPONSE,
                    "The model provider returned an unreadable response.");
        }
        return new ModelCallFailure(false, ErrorCode.MODEL_SERVICE_ERROR, "Unexpected model provider failure.");
    }

    private static boolean isTimeoutFailure(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (current instanceof SocketTimeoutException || current instanceof ConnectException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    static boolean isTransientTransportFailure(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (current instanceof SocketTimeoutException
                    || current instanceof ConnectException
                    || current instanceof SocketException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static java.time.Duration shorter(java.time.Duration first, java.time.Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private record ModelCallFailure(boolean retryable, ErrorCode errorCode, String message, BusinessException original) {

        ModelCallFailure(boolean retryable, ErrorCode errorCode, String message) {
            this(retryable, errorCode, message, null);
        }

        ModelCallFailure(boolean retryable, BusinessException original) {
            this(retryable, original.errorCode(), original.getMessage(), original);
        }

        BusinessException toBusinessException() {
            if (original != null) {
                return original;
            }
            return new BusinessException(errorCode, message);
        }
    }

    private static void sleep(long nanos) {
        try {
            Thread.sleep(nanos / 1_000_000L, (int) (nanos % 1_000_000L));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.MODEL_TIMEOUT,
                    "Model provider call was interrupted while retrying.");
        }
    }

    private static EmbeddingMetrics.Outcome outcomeFor(ErrorCode errorCode) {
        return switch (errorCode) {
            case MODEL_AUTH_FAILED -> EmbeddingMetrics.Outcome.AUTH_FAILED;
            case MODEL_RATE_LIMITED -> EmbeddingMetrics.Outcome.RATE_LIMITED;
            case MODEL_TIMEOUT -> EmbeddingMetrics.Outcome.TIMEOUT;
            case MODEL_BAD_RESPONSE -> EmbeddingMetrics.Outcome.BAD_RESPONSE;
            case MODEL_SERVICE_ERROR -> EmbeddingMetrics.Outcome.SERVICE_ERROR;
            default -> EmbeddingMetrics.Outcome.CONFIGURATION_ERROR;
        };
    }
}
