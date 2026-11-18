package com.superup.gateway.gateway;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Layer 1, check "is it new?" (docs/core/api-conventions.md section 6).
 *
 * <ul>
 *   <li>Same key, same request: the first answer comes back, marked Idempotent-Replayed.</li>
 *   <li>Same key, different request: 409 idempotency_conflict.</li>
 *   <li>Same key while the first request is still running: 409 request_in_progress.</li>
 * </ul>
 * So a client that retries after a network error never creates two payments.
 */
@Service
public class IdempotencyService {

    /** An earlier answer to replay. */
    public record StoredResponse(int status, String body) {
    }

    private record Row(String requestHash, String status, Integer responseStatus, String responseBody,
                       Instant expiresAt) {
    }

    private final JdbcTemplate db;

    public IdempotencyService(JdbcTemplate db) {
        this.db = db;
    }

    public static String requireValidKey(String key) {
        if (key == null || key.isBlank()) {
            throw ApiException.invalid("idempotency_key_missing",
                    "Send an Idempotency-Key header with every request that creates something.", "Idempotency-Key");
        }
        if (key.length() > 255) {
            throw ApiException.invalid("idempotency_key_invalid",
                    "Idempotency-Key must be 1 to 255 characters.", "Idempotency-Key");
        }
        return key;
    }

    /**
     * Claims the key for this request. Returns empty when the caller should do the work,
     * or the earlier answer when this is a repeat. Runs in its own short transaction.
     */
    public Optional<StoredResponse> claim(Caller caller, String endpoint, String key, String requestHash,
                                          Instant expiresAt) {
        for (int tries = 0; tries < 3; tries++) {
            int inserted = db.update("""
                    INSERT INTO idempotency_keys (merchant_id, mode, endpoint, key, request_hash, status, expires_at)
                    VALUES (?, ?, ?, ?, ?, 'IN_PROGRESS', ?)
                    ON CONFLICT DO NOTHING
                    """, caller.merchantId(), caller.mode(), endpoint, key, requestHash, Timestamp.from(expiresAt));
            if (inserted == 1) {
                return Optional.empty();
            }
            List<Row> rows = db.query("""
                    SELECT request_hash, status, response_status, response_body, expires_at
                      FROM idempotency_keys
                     WHERE merchant_id = ? AND mode = ? AND endpoint = ? AND key = ?
                    """,
                    (rs, i) -> new Row(rs.getString(1), rs.getString(2), (Integer) rs.getObject(3),
                            rs.getString(4), rs.getTimestamp(5).toInstant()),
                    caller.merchantId(), caller.mode(), endpoint, key);
            if (rows.isEmpty()) {
                continue;   // it was just removed; try again
            }
            Row row = rows.get(0);
            if (row.expiresAt().isBefore(Instant.now())) {
                db.update("""
                        DELETE FROM idempotency_keys
                         WHERE merchant_id = ? AND mode = ? AND endpoint = ? AND key = ? AND expires_at < now()
                        """, caller.merchantId(), caller.mode(), endpoint, key);
                continue;
            }
            if (!row.requestHash().equals(requestHash)) {
                throw ApiException.idempotencyConflict();
            }
            if ("IN_PROGRESS".equals(row.status())) {
                throw ApiException.inProgress();
            }
            return Optional.of(new StoredResponse(row.responseStatus(), row.responseBody()));
        }
        throw ApiException.inProgress();
    }

    /** Saves the answer. Call it inside the same transaction as the work, so both commit together. */
    public void complete(Caller caller, String endpoint, String key, int status, String body) {
        db.update("""
                UPDATE idempotency_keys SET status = 'DONE', response_status = ?, response_body = ?
                 WHERE merchant_id = ? AND mode = ? AND endpoint = ? AND key = ?
                """, status, body, caller.merchantId(), caller.mode(), endpoint, key);
    }

    /** Frees the key when the work failed, so the client can retry with the same key. */
    public void release(Caller caller, String endpoint, String key) {
        db.update("""
                DELETE FROM idempotency_keys
                 WHERE merchant_id = ? AND mode = ? AND endpoint = ? AND key = ? AND status = 'IN_PROGRESS'
                """, caller.merchantId(), caller.mode(), endpoint, key);
    }
}
