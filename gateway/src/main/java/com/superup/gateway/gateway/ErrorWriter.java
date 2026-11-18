package com.superup.gateway.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/** Writes the standard error body: {"error": {type, code, message, param, requestId}}. */
@Component
public class ErrorWriter {

    private final ObjectMapper json;

    public ErrorWriter(ObjectMapper json) {
        this.json = json;
    }

    public Map<String, Object> body(ApiException e, HttpServletRequest request) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("type", e.type());
        error.put("code", e.code());
        error.put("message", e.getMessage());
        error.put("param", e.param());
        error.put("requestId", RequestIdFilter.requestId(request));
        return Map.of("error", error);
    }

    /** Used by filters, which run before Spring MVC's error handling. */
    public void write(ApiException e, HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(e.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), body(e, request));
    }
}
