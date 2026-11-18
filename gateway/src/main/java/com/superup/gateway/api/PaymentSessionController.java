package com.superup.gateway.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.superup.gateway.GatewayProperties;
import com.superup.gateway.gateway.ApiException;
import com.superup.gateway.gateway.ApiKeyAuthFilter;
import com.superup.gateway.gateway.Caller;
import com.superup.gateway.gateway.IdempotencyService;
import com.superup.gateway.gateway.PaymentRequestCheck;
import com.superup.gateway.orchestrator.NewPayment;
import com.superup.gateway.orchestrator.PaymentService;
import com.superup.gateway.orchestrator.PaymentSessionView;
import com.superup.gateway.record.PaymentRow;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Payment API for clients' servers (payment-api.openapi.yaml). Every request has already passed
 * the layer 1 key and rate-limit checks. Here we check the request itself, then hand it to the orchestrator.
 */
@RestController
@RequestMapping("/api/v1")
public class PaymentSessionController {

    private static final String CREATE_ENDPOINT = "POST /payment-sessions";

    private final PaymentRequestCheck check;
    private final IdempotencyService idempotency;
    private final PaymentService payments;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;
    private final GatewayProperties properties;

    public PaymentSessionController(PaymentRequestCheck check, IdempotencyService idempotency,
                                    PaymentService payments, TransactionTemplate transaction, ObjectMapper json,
                                    GatewayProperties properties) {
        this.check = check;
        this.idempotency = idempotency;
        this.payments = payments;
        this.transaction = transaction;
        this.json = json;
        this.properties = properties;
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

        try {
            String answer = transaction.execute(status -> {
                PaymentRow row = payments.create(caller.merchantId(), caller.mode(), caller.keyId(), caller.ip(),
                        payment);
                String text = toJson(view(row));
                idempotency.complete(caller, CREATE_ENDPOINT, key, HttpStatus.CREATED.value(), text);
                return text;
            });
            return ResponseEntity.status(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(answer);
        } catch (RuntimeException e) {
            idempotency.release(caller, CREATE_ENDPOINT, key);
            throw e;
        }
    }

    @GetMapping("/payment-sessions/{id}")
    public PaymentSessionView read(HttpServletRequest request, @PathVariable String id) {
        Caller caller = Caller.of(request);
        return payments.find(caller.merchantId(), caller.mode(), id)
                .map(this::view)
                .orElseThrow(() -> ApiException.notFound("Payment session " + id));
    }

    /** Call this before delivering goods. */
    @GetMapping("/payments/verify/{orderReference}")
    public PaymentSessionView verify(HttpServletRequest request, @PathVariable String orderReference) {
        Caller caller = Caller.of(request);
        return payments.latestForOrder(caller.merchantId(), caller.mode(), orderReference)
                .map(this::view)
                .orElseThrow(() -> ApiException.notFound("A payment for order " + orderReference));
    }

    private PaymentSessionView view(PaymentRow row) {
        return PaymentSessionView.of(row, properties.checkoutBaseUrl());
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

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
