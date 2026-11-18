package com.superup.gateway.gateway;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Turns every error into the standard error shape, with the request ID. */
@RestControllerAdvice
public class GlobalErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalErrorHandler.class);

    private final ErrorWriter errors;

    public GlobalErrorHandler(ErrorWriter errors) {
        this.errors = errors;
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException e, HttpServletRequest request) {
        ResponseEntity.BodyBuilder answer = ResponseEntity.status(e.status());
        if ("rate_limited".equals(e.type())) {
            answer.header("Retry-After", "1");
        }
        return answer.body(errors.body(e, request));
    }

    /** Bad JSON, an unknown field, or a number where a string was expected (for example a float amount). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        ApiException error;
        if (e.getCause() instanceof UnrecognizedPropertyException unknown) {
            error = ApiException.invalid("parameter_unknown",
                    "The field " + unknown.getPropertyName() + " is not allowed here.", unknown.getPropertyName());
        } else if (e.getCause() instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()) {
            String field = mapping.getPath().stream()
                    .map(r -> r.getFieldName() != null ? r.getFieldName() : String.valueOf(r.getIndex()))
                    .collect(Collectors.joining("."));
            error = "amount".equals(field)
                    ? ApiException.invalid("amount_invalid",
                    "amount must be a string with two decimals, for example \"1500.00\". Numbers are not accepted.",
                    "amount")
                    : ApiException.invalid("parameter_invalid", "The field " + field + " has the wrong type.", field);
        } else {
            error = ApiException.invalid("body_malformed", "The request body is not valid JSON.", null);
        }
        return api(error, request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Map<String, Object>> noRoute(NoResourceFoundException e, HttpServletRequest request) {
        return api(new ApiException(HttpStatus.NOT_FOUND, "not_found", "route_missing",
                "There is no such endpoint.", null), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Map<String, Object>> method(HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        return api(new ApiException(HttpStatus.METHOD_NOT_ALLOWED, "invalid_request", "method_not_allowed",
                "This endpoint does not accept " + e.getMethod() + ".", null), request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Map<String, Object>> mediaType(HttpMediaTypeNotSupportedException e, HttpServletRequest request) {
        return api(new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "invalid_request", "content_type_invalid",
                "Send the body as application/json.", null), request);
    }

    /** Our fault. The details go to the log, never to the client. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception e, HttpServletRequest request) {
        log.error("Unexpected error for request {}", RequestIdFilter.requestId(request), e);
        return api(new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "api_error", "internal_error",
                "Something went wrong on our side. Retry with the same Idempotency-Key.", null), request);
    }
}
