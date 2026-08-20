package com.knowagent.model.infrastructure.embedding;

import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResponseErrorHandler;

import java.io.IOException;

/**
 * Replaces Spring AI's default error handler for the OpenAI-compatible client. The
 * default handler embeds the raw provider response body in its exception message, which
 * could echo a provider secret or other sensitive payload (Rule 10 / prompt: errors
 * must not return the vendor's raw sensitive body). This handler instead throws a
 * {@link ModelCallException} carrying only the HTTP status number. The body is never
 * read.
 */
final class OpenAiResponseErrorHandler implements ResponseErrorHandler {

    @Override
    public boolean hasError(ClientHttpResponse response) throws IOException {
        return response.getStatusCode().isError();
    }

    @Override
    public void handleError(ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        ModelCallException.Kind kind = switch (status) {
            case 401, 403 -> ModelCallException.Kind.AUTH;
            case 429 -> ModelCallException.Kind.RATE_LIMITED;
            default -> status >= 500 ? ModelCallException.Kind.TRANSIENT_SERVICE
                    : ModelCallException.Kind.CLIENT_CONFIG;
        };
        throw new ModelCallException(kind, status);
    }
}
