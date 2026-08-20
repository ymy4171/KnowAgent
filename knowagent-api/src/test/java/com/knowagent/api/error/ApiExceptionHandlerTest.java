package com.knowagent.api.error;

import com.knowagent.common.error.ErrorCode;
import com.knowagent.knowledge.infrastructure.vector.VectorStoreException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void vectorStoreFailuresUseTheUniformBusinessErrorMapping() {
        assertMapped(ErrorCode.VECTOR_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE);
        assertMapped(ErrorCode.VECTOR_BAD_RESPONSE, HttpStatus.BAD_GATEWAY);
        assertMapped(ErrorCode.VECTOR_SCHEMA_MISMATCH, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private void assertMapped(ErrorCode code, HttpStatus expectedStatus) {
        String safeMessage = "Safe vector-store failure.";
        ResponseEntity<ApiErrorResponse> response = handler.business(new VectorStoreException(code, safeMessage));

        assertThat(response.getStatusCode()).isEqualTo(expectedStatus);
        assertThat(response.getBody()).isEqualTo(new ApiErrorResponse(code.name(), safeMessage));
    }
}
