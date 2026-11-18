package com.superup.gateway.record;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Layer 4: parts 1 to 4 of the record (docs/08-data-model.md).
 * Part 1 rows are success candidates. Parts 2 and 3 only ever get new rows.
 * The status is never stored: it is read from payments_view.
 */
@Repository
public class PaymentRecords {

    private final JdbcTemplate db;

    public PaymentRecords(JdbcTemplate db) {
        this.db = db;
    }

    // ---- Part 1: success candidates ----

    public void insertSession(String id, String merchantId, String mode, String orderReference, long amount,
                              String currency, String description, String returnUrl, String webhookUrl,
                              String metadataJson, Instant expiresAt) {
        db.update("""
                INSERT INTO payment_sessions (id, merchant_id, mode, order_reference, amount, currency, description,
                                              return_url, webhook_url, metadata, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, id, merchantId, mode, orderReference, amount, currency, description, returnUrl, webhookUrl,
                metadataJson, Timestamp.from(expiresAt));
    }

    public void insertAttempt(String id, String sessionId, String mode, String method, String providerId,
                              String payerMasked) {
        db.update("""
                INSERT INTO payment_attempts (id, session_id, mode, method, provider_id, payer_masked)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, sessionId, mode, method, providerId, payerMasked);
    }

    /** Moves a running try forward, for example from waiting for the PIN to waiting for the bank. */
    public void moveAttempt(String attemptId, String step, String providerRef) {
        db.update("""
                UPDATE payment_attempts SET step = ?, provider_ref = coalesce(provider_ref, ?) WHERE id = ?
                """, step, providerRef, attemptId);
    }

    public Optional<Attempt> findAttempt(String attemptId) {
        return db.query("""
                SELECT a.id, a.session_id, a.mode, a.method, a.provider_id, p.code AS provider_code, a.provider_ref
                  FROM payment_attempts a JOIN providers p ON p.id = a.provider_id
                 WHERE a.id = ?
                """, (rs, i) -> new Attempt(rs.getString("id"), rs.getString("session_id"), rs.getString("mode"),
                rs.getString("method"), rs.getString("provider_id"), rs.getString("provider_code"),
                rs.getString("provider_ref")), attemptId).stream().findFirst();
    }

    public record Attempt(String id, String sessionId, String mode, String method, String providerId,
                          String providerCode, String providerRef) {
    }

    // ---- Part 2: successes ----

    public void insertSuccess(String id, String sessionId, String attemptId, String mode, long amount, long fee,
                              long net, String providerRef, String confirmedBy, String journalId,
                              String correctsFailureId, String approvalId) {
        db.update("""
                INSERT INTO payment_successes (id, session_id, attempt_id, mode, amount, fee, net, provider_ref,
                                               confirmed_by, journal_id, corrects_failure_id, approval_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, sessionId, attemptId, mode, amount, fee, net, providerRef, confirmedBy, journalId,
                correctsFailureId, approvalId);
    }

    // ---- Part 3: failures ----

    /** attemptId is null when the whole payment ended (EXPIRED or CANCELLED). */
    public void insertFailure(String id, String sessionId, String attemptId, String mode, String reason,
                              String providerCode) {
        db.update("""
                INSERT INTO payment_failures (id, session_id, attempt_id, mode, reason, provider_code)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, sessionId, attemptId, mode, reason, providerCode);
    }

    // ---- Part 4: the view ----

    private static final String VIEW_COLUMNS = """
            session_id, merchant_id, mode, order_reference, amount, currency, description, return_url,
            metadata::text AS metadata, expires_at, created_at, fee, net, paid_at, paid_method, paid_payer_masked,
            amount_refunded, tries, last_failure_reason, status
            """;

    /** Reads one payment, only for its own client and mode. */
    public Optional<PaymentRow> find(String merchantId, String mode, String sessionId) {
        return db.query("SELECT " + VIEW_COLUMNS + " FROM payments_view WHERE session_id = ? AND merchant_id = ? AND mode = ?",
                PaymentRecords::row, sessionId, merchantId, mode).stream().findFirst();
    }

    public Optional<PaymentRow> find(String sessionId) {
        return db.query("SELECT " + VIEW_COLUMNS + " FROM payments_view WHERE session_id = ?",
                PaymentRecords::row, sessionId).stream().findFirst();
    }

    /** The newest payment for the client's own order reference. */
    public Optional<PaymentRow> latestForOrder(String merchantId, String mode, String orderReference) {
        return db.query("SELECT " + VIEW_COLUMNS + """
                 FROM payments_view WHERE merchant_id = ? AND mode = ? AND order_reference = ?
                 ORDER BY created_at DESC LIMIT 1
                """, PaymentRecords::row, merchantId, mode, orderReference).stream().findFirst();
    }

    /**
     * Payments whose time ran out, with no result and no try running. A try still waiting for the
     * customer must first be closed with the provider (it may have been approved at the last second).
     */
    public List<String> dueForExpiry(int limit) {
        return db.queryForList("""
                SELECT v.session_id FROM payments_view v
                 WHERE v.status IN ('CREATED', 'AUTHORIZATION_REQUIRED') AND v.expires_at < now()
                   AND NOT EXISTS (SELECT 1 FROM payment_attempts a
                                    WHERE a.session_id = v.session_id
                                      AND NOT EXISTS (SELECT 1 FROM payment_failures f WHERE f.attempt_id = a.id))
                 ORDER BY v.expires_at LIMIT ?
                """, String.class, limit);
    }

    private static PaymentRow row(ResultSet rs, int i) throws SQLException {
        return new PaymentRow(
                rs.getString("session_id"),
                rs.getString("merchant_id"),
                rs.getString("mode"),
                rs.getString("order_reference"),
                rs.getLong("amount"),
                rs.getString("currency"),
                rs.getString("description"),
                rs.getString("return_url"),
                rs.getString("metadata"),
                instant(rs, "expires_at"),
                instant(rs, "created_at"),
                (Long) rs.getObject("fee"),
                (Long) rs.getObject("net"),
                instant(rs, "paid_at"),
                rs.getString("paid_method"),
                rs.getString("paid_payer_masked"),
                rs.getLong("amount_refunded"),
                rs.getInt("tries"),
                rs.getString("last_failure_reason"),
                rs.getString("status"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
