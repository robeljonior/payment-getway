package com.superup.gateway.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superup.gateway.GatewayProperties;
import com.superup.gateway.gateway.ApiException;
import com.superup.gateway.gateway.Caller;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Calls the orchestrator's internal API over HTTP. Every call carries the shared secret
 * (X-Internal-Token) and the request ID, so one request can be followed through both services.
 */
@Component
public class HttpOrchestrator implements Orchestrator {

    private static final Logger log = LoggerFactory.getLogger(HttpOrchestrator.class);

    private final HttpClient http;
    private final ObjectMapper json;
    private final String baseUrl;
    private final String token;
    private final Duration timeout;

    public HttpOrchestrator(ObjectMapper json, GatewayProperties properties) {
        GatewayProperties.OrchestratorSettings settings = properties.orchestrator();
        this.json = json;
        this.baseUrl = settings.url().replaceAll("/+$", "");
        this.token = settings.token();
        this.timeout = Duration.ofMillis(settings.timeoutMs());
        this.http = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    @Override
    public String create(Caller caller, String createKey, NewPayment p) {
        ObjectNode body = json.createObjectNode()
                .put("createKey", createKey)
                .put("merchantId", caller.merchantId())
                .put("mode", caller.mode())
                .put("actorKeyId", caller.keyId())
                .put("orderReference", p.orderReference())
                .put("amount", p.amount())
                .put("currency", p.currency())
                .put("description", p.description())
                .put("returnUrl", p.returnUrl())
                .put("webhookUrl", p.webhookUrl())
                .put("expiresInSeconds", p.expiresInSeconds());
        try {
            body.set("metadata", json.readTree(p.metadataJson()));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        HttpResponse<String> answer = send(caller, HttpRequest.newBuilder(uri("/internal/v1/payments"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())));
        if (answer.statusCode() == 200 || answer.statusCode() == 201) {
            return answer.body();
        }
        throw refused(answer);
    }

    @Override
    public Optional<String> find(Caller caller, String sessionId) {
        return read(caller, "/internal/v1/payments/" + encode(sessionId) + client(caller));
    }

    @Override
    public Optional<String> latestForOrder(Caller caller, String orderReference) {
        return read(caller, "/internal/v1/payments/by-order/" + encode(orderReference) + client(caller));
    }

    private Optional<String> read(Caller caller, String path) {
        HttpResponse<String> answer = send(caller, HttpRequest.newBuilder(uri(path)).GET());
        return switch (answer.statusCode()) {
            case 200 -> Optional.of(answer.body());
            case 404 -> Optional.empty();
            default -> throw refused(answer);
        };
    }

    private HttpResponse<String> send(Caller caller, HttpRequest.Builder request) {
        request.timeout(timeout).header("X-Internal-Token", token);
        String requestId = MDC.get("requestId");
        if (requestId != null) {
            request.header("X-Request-Id", requestId);
        }
        if (caller.ip() != null) {
            request.header("X-Client-Ip", caller.ip());
        }
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            log.warn("Orchestrator not reachable: {}", e.toString());
            throw unavailable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
    }

    /**
     * The gateway has already checked the request, so a refusal here means the payment's state or a
     * database rule said no. The orchestrator's code and message are passed on.
     */
    private ApiException refused(HttpResponse<String> answer) {
        int status = answer.statusCode();
        if (status >= 500 || status == 401) {
            log.error("Orchestrator answered {}: {}", status, answer.body());
            throw unavailable();
        }
        String code = "orchestrator_refused";
        String message = "The payment could not be saved.";
        try {
            JsonNode error = json.readTree(answer.body()).path("error");
            code = error.path("code").asText(code);
            message = error.path("message").asText(message);
        } catch (IOException ignored) {
            // keep the defaults
        }
        return status == 409
                ? new ApiException(HttpStatus.CONFLICT, "state_conflict", code, message, null)
                : ApiException.invalid(code, message, null);
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "api_error", "service_unavailable",
                "Payments are not available right now. Retry with the same Idempotency-Key.", null);
    }

    private URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    private static String client(Caller caller) {
        return "?merchantId=" + encode(caller.merchantId()) + "&mode=" + encode(caller.mode());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
