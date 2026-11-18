package com.superup.gateway.gateway;

import org.springframework.http.HttpStatus;

/**
 * An error in the standard shape from docs/core/api-conventions.md section 5.
 * The HTTP status always matches the type.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String type;
    private final String code;
    private final String param;

    public ApiException(HttpStatus status, String type, String code, String message, String param) {
        super(message);
        this.status = status;
        this.type = type;
        this.code = code;
        this.param = param;
    }

    public static ApiException invalid(String code, String message, String param) {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid_request", code, message, param);
    }

    /** Never says whether the key exists. */
    public static ApiException unauthenticated() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "authentication_failed", "invalid_api_key",
                "The API key is missing, wrong or revoked.", null);
    }

    public static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "permission_denied", code, message, null);
    }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", "resource_missing",
                what + " was not found.", null);
    }

    public static ApiException idempotencyConflict() {
        return new ApiException(HttpStatus.CONFLICT, "idempotency_conflict", "idempotency_key_reused",
                "This Idempotency-Key was already used with a different request.", "Idempotency-Key");
    }

    public static ApiException inProgress() {
        return new ApiException(HttpStatus.CONFLICT, "state_conflict", "request_in_progress",
                "A request with this Idempotency-Key is still running. Retry shortly.", "Idempotency-Key");
    }

    public static ApiException rateLimited() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "rate_limited",
                "Too many requests. Retry after the number of seconds in Retry-After.", null);
    }

    public HttpStatus status() {
        return status;
    }

    public String type() {
        return type;
    }

    public String code() {
        return code;
    }

    public String param() {
        return param;
    }
}
