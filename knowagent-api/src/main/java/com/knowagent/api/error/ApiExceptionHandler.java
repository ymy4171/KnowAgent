package com.knowagent.api.error;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * Translates exceptions raised in controllers into the API's uniform JSON error
 * shape with stable error codes.
 *
 * <p>{@link BusinessException} carries its own {@link ErrorCode}, which maps to an
 * HTTP status below. DTO validation failures and malformed JSON bodies are reported
 * as a unified 400 {@code VALIDATION_ERROR}. Anything else becomes a generic 500
 * {@code INTERNAL_ERROR} whose body never echoes the exception message (it could
 * contain credentials or tokens); the cause is only logged.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiErrorResponse> business(BusinessException exception) {
        HttpStatus status = statusFor(exception.errorCode());
        return ResponseEntity.status(status)
                .body(new ApiErrorResponse(exception.errorCode().name(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> validation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(new ApiErrorResponse(ErrorCode.VALIDATION_ERROR.name(), message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> malformedBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiErrorResponse(ErrorCode.VALIDATION_ERROR.name(),
                        "The request body is missing or malformed."));
    }

    /**
     * A query or path parameter that cannot be converted to its declared type (for
     * example an unknown {@code status} enum value or a non-UUID user id) is a
     * client error, so it must be a 400 rather than a 500.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> typeMismatch(MethodArgumentTypeMismatchException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiErrorResponse(ErrorCode.VALIDATION_ERROR.name(),
                        "Invalid value for parameter '" + exception.getName() + "'."));
    }

    /**
     * A route with no matching handler is a 404, not an internal error. This is
     * what lets permit-all routes without controllers answer 404 instead of 500.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> noResource(NoResourceFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse(ErrorCode.RESOURCE_NOT_FOUND.name(),
                        "The requested resource does not exist."));
    }

    // Deliberately no catch-all @ExceptionHandler(Exception.class): it would run
    // inside the DispatcherServlet and swallow exceptions that belong to Spring
    // Security's filter chain (an AccessDeniedException from method security must
    // propagate to JsonAccessDeniedHandler for the JSON 403, and a 405/415 must
    // keep its own status) - turning them into 500 instead.

    private static HttpStatus statusFor(ErrorCode errorCode) {
        return switch (errorCode) {
            case VALIDATION_ERROR -> HttpStatus.BAD_REQUEST;
            case AUTHENTICATION_REQUIRED -> HttpStatus.UNAUTHORIZED;
            case INVALID_CREDENTIALS -> HttpStatus.UNAUTHORIZED;
            case ACCESS_DENIED -> HttpStatus.FORBIDDEN;
            case ACCOUNT_DISABLED -> HttpStatus.FORBIDDEN;
            case ACCOUNT_LOCKED -> HttpStatus.FORBIDDEN;
            case RESOURCE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case EXTERNAL_SERVICE_ERROR -> HttpStatus.BAD_GATEWAY;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
