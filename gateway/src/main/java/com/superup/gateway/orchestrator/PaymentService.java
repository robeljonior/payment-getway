package com.superup.gateway.orchestrator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superup.gateway.GatewayProperties;
import com.superup.gateway.common.Ids;
import com.superup.gateway.common.Money;
import com.superup.gateway.record.AuditLog;
import com.superup.gateway.record.Ledger;
import com.superup.gateway.record.Ledger.Line;
import com.superup.gateway.record.Outbox;
import com.superup.gateway.record.PaymentRecords;
import com.superup.gateway.record.PaymentRow;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Layer 2, the orchestrator: decides what happens next for every payment.
 * It is the only place that decides that a payment succeeded or failed, and it always writes
 * the result, the ledger journal and the event together, in one transaction.
 */
@Service
public class PaymentService {

    private final PaymentRecords records;
    private final Ledger ledger;
    private final Outbox outbox;
    private final AuditLog audit;
    private final ObjectMapper json;
    private final GatewayProperties properties;

    public PaymentService(PaymentRecords records, Ledger ledger, Outbox outbox, AuditLog audit, ObjectMapper json,
                          GatewayProperties properties) {
        this.records = records;
        this.ledger = ledger;
        this.outbox = outbox;
        this.audit = audit;
        this.json = json;
        this.properties = properties;
    }

    // ---- Part 1: a new success candidate ----

    /** Saves a new payment request. Call it inside a transaction (the API also saves the idempotency answer). */
    public PaymentRow create(String merchantId, String mode, String keyId, String ip, NewPayment p) {
        String id = Ids.newId(Ids.SESSION);
        records.insertSession(id, merchantId, mode, p.orderReference(), p.amount(), p.currency(), p.description(),
                p.returnUrl(), p.webhookUrl(), p.metadataJson(), Instant.now().plusSeconds(p.expiresInSeconds()));
        audit.record("merchant_key", keyId, "payment_session.created", "payment_session", id,
                toJson(Map.of("amount", p.amount(), "orderReference", p.orderReference())), ip);
        return records.find(id).orElseThrow();
    }

    public Optional<PaymentRow> find(String merchantId, String mode, String sessionId) {
        return records.find(merchantId, mode, sessionId);
    }

    public Optional<PaymentRow> latestForOrder(String merchantId, String mode, String orderReference) {
        return records.latestForOrder(merchantId, mode, orderReference);
    }

    // ---- Tries (used by checkout and the connectors, which come next) ----

    @Transactional
    public String startAttempt(String sessionId, String method, String providerId, String payerMasked) {
        PaymentRow row = records.find(sessionId).orElseThrow();
        boolean open = row.status().equals("CREATED") || row.status().equals("AUTHORIZATION_REQUIRED");
        if (!open || row.expiresAt().isBefore(Instant.now())) {
            throw new IllegalStateException("Payment " + sessionId + " is " + row.status()
                    + " and can't start a new try");
        }
        String id = Ids.newId(Ids.ATTEMPT);
        records.insertAttempt(id, sessionId, row.mode(), method, providerId, payerMasked);
        return id;
    }

    @Transactional
    public void moveAttempt(String attemptId, String step, String providerRef) {
        records.moveAttempt(attemptId, step, providerRef);
    }

    // ---- Part 2: a success ----

    /** The bank confirmed the money. Writes the success, ledger journal J1 and payment.succeeded together. */
    @Transactional
    public PaymentRow recordSuccess(String attemptId, String providerRef, String confirmedBy) {
        return recordSuccess(attemptId, providerRef, confirmedBy, null, null);
    }

    /**
     * Same, for a late success that reconciliation found after the try was recorded as failed.
     * It needs a two-person approval, and the failure row stays as it was.
     */
    @Transactional
    public PaymentRow recordSuccess(String attemptId, String providerRef, String confirmedBy,
                                    String correctsFailureId, String approvalId) {
        PaymentRecords.Attempt attempt = records.findAttempt(attemptId).orElseThrow();
        PaymentRow before = records.find(attempt.sessionId()).orElseThrow();
        long amount = before.amount();
        long fee = Money.fee(amount, properties.feeBasisPoints());
        long net = amount - fee;

        // Journal J1 (docs/05-money-and-ledger.md): the provider owes us the money,
        // the client is owed the net amount, and the fee is our revenue.
        String journalId = ledger.post("payment_success", "payment_success:" + before.sessionId(), before.mode(),
                List.of(Line.debit("provider_receivable:" + attempt.providerCode(), "asset", amount),
                        Line.credit("merchant:" + before.merchantId() + ":pending", "liability", net),
                        Line.credit("fees:gateway", "revenue", fee)));

        records.insertSuccess(Ids.newId(Ids.SUCCESS), before.sessionId(), attemptId, before.mode(), amount, fee, net,
                providerRef != null ? providerRef : attempt.providerRef(), confirmedBy, journalId,
                correctsFailureId, approvalId);

        PaymentRow after = records.find(before.sessionId()).orElseThrow();
        event(after, "payment.succeeded");
        audit.record("system", "orchestrator", "payment.succeeded", "payment_session", after.sessionId(),
                toJson(Map.of("attemptId", attemptId, "journalId", journalId, "fee", fee, "net", net)), null);
        return after;
    }

    // ---- Part 3: failures ----

    /** One try failed. The payment stays open, so the customer can try another way. */
    @Transactional
    public PaymentRow recordTryFailure(String attemptId, String reason, String providerCode) {
        PaymentRecords.Attempt attempt = records.findAttempt(attemptId).orElseThrow();
        records.insertFailure(Ids.newId(Ids.FAILURE), attempt.sessionId(), attemptId, attempt.mode(), reason,
                providerCode);
        audit.record("system", "orchestrator", "payment_attempt.failed", "payment_attempt", attemptId,
                toJson(Map.of("reason", reason)), null);
        return records.find(attempt.sessionId()).orElseThrow();
    }

    /** The whole payment ended without money: EXPIRED (time ran out) or CANCELLED (the customer cancelled). */
    @Transactional
    public PaymentRow endPayment(String sessionId, String reason) {
        PaymentRow before = records.find(sessionId).orElseThrow();
        records.insertFailure(Ids.newId(Ids.FAILURE), sessionId, null, before.mode(), reason, null);
        PaymentRow after = records.find(sessionId).orElseThrow();
        event(after, "EXPIRED".equals(reason) ? "payment.expired" : "payment.failed");
        audit.record("system", "orchestrator", "payment_session.ended", "payment_session", sessionId,
                toJson(Map.of("reason", reason)), null);
        return after;
    }

    public List<String> dueForExpiry(int limit) {
        return records.dueForExpiry(limit);
    }

    // ---- helpers ----

    private void event(PaymentRow row, String type) {
        String eventId = Ids.newId(Ids.EVENT);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", eventId);
        event.put("type", type);
        event.put("apiVersion", "1.0");
        event.put("mode", row.mode());
        event.put("createdAt", Instant.now());
        event.put("data", Map.of("object", PaymentSessionView.of(row, properties.checkoutBaseUrl())));
        outbox.add(eventId, row.merchantId(), row.mode(), type, toJson(event));
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
