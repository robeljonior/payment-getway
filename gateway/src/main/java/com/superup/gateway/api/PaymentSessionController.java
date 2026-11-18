package com.superup.gateway.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.superup.gateway.gateway.ApiException;
import com.superup.gateway.gateway.ApiKeyAuthFilter;
import com.superup.gateway.gateway.Caller;
import com.superup.gateway.gateway.IdempotencyService;
import com.superup.gateway.gateway.PaymentRequestCheck;
import com.superup.gateway.orchestrator.NewPayment;
import com.superup.gateway.orchestrator.Orchestrator;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Payment API for clients' servers (payment-api.openapi.yaml). Every request has already passed
 * the layer 1 key and rate-limit checks. Here we check the request itself, then hand it to the orchestrator
 * service, and pass its answer on to the client as it is.
 */
@RestController
@RequestMapping("/api/v1")
public class PaymentSessionController {

    private static final String CREATE_ENDPOINT = "POST /payment-sessions";

    private final PaymentRequestCheck check;
    private final IdempotencyService idempotency;
    private final Orchestrator orchestrator;
    private final ObjectMapper json;

    public PaymentSessionController(PaymentRequestCheck check, IdempotencyService idempotency,
                                    Orchestrator orchestrator, ObjectMapper json) {
        this.check = check;
        this.idempotency = idempotency;
        this.orchestrator = orchestrator;
        this.json = json;
    }

    @PostMapping("/payment-sessions")
    public ResponseEntity<String> create(HttpServletRequest request,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                         @RequestBody(required = false) CreatePaymentSessionRequest body) {
        Caller caller = Caller.of(request);
        caller.mustBeAllowedToCreatePayments();
        IdempotencyService.requireValidKey(key);
        NewPayment payment = check.check(caller, body);

        Instant keepUntil = Instant.now().plus(Duration.ofHours(24)).plusSeconds(payment.expiresInSeconds());
        Optional<IdempotencyService.StoredResponse> earlier =
                idempotency.claim(caller, CREATE_ENDPOINT, key, requestHash(body), keepUntil);
        if (earlier.isPresent()) {
            return ResponseEntity.ok()
                    .header("Idempotent-Replayed", "true")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(earlier.get().body());
        }

        // The orchestrator gets a create key made from this client, mode and Idempotency-Key. If the
        // gateway stops halfway and the client retries, the orchestrator returns the same payment.
        String createKey = ApiKeyAuthFilter.sha256(
                caller.merchantId() + "|" + caller.mode() + "|" + CREATE_ENDPOINT + "|" + key);
        String answer;
        try {
            answer = orchestrator.create(caller, createKey, payment);
        } catch (RuntimeException e) {
            idempotency.release(caller, CREATE_ENDPOINT, key);
            throw e;
        }
        idempotency.complete(caller, CREATE_ENDPOINT, key, HttpStatus.CREATED.value(), answer);
        return ResponseEntity.status(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(answer);
    }

    @GetMapping("/payment-sessions/{id}")
    public ResponseEntity<String> read(HttpServletRequest request, @PathVariable String id) {
        Caller caller = Caller.of(request);
        return orchestrator.find(caller, id)
                .map(PaymentSessionController::ok)
                .orElseThrow(() -> ApiException.notFound("Payment session " + id));
    }

    /** Call this before delivering goods. */
    @GetMapping("/payments/verify/{orderReference}")
    public ResponseEntity<String> verify(HttpServletRequest request, @PathVariable String orderReference) {
        Caller caller = Caller.of(request);
        return orchestrator.latestForOrder(caller, orderReference)
                .map(PaymentSessionController::ok)
                .orElseThrow(() -> ApiException.notFound("A payment for order " + orderReference));
    }

    private static ResponseEntity<String> ok(String body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }

    /** The same request always gives the same hash, whatever the order of the metadata keys. */
    private String requestHash(CreatePaymentSessionRequest body) {
        try {
            String canonical = json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(body);
            return ApiKeyAuthFilter.sha256(canonical);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

}
