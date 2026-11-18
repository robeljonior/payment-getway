package com.superup.gateway.record;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Layer 4: the outbox. An event is saved in the same transaction as the result it describes.
 * A worker sends it to the client as a signed webhook (that worker comes in a later step).
 */
@Repository
public class Outbox {

    private final JdbcTemplate db;

    public Outbox(JdbcTemplate db) {
        this.db = db;
    }

    public void add(String eventId, String merchantId, String mode, String type, String payloadJson) {
        db.update("""
                INSERT INTO outbox_events (id, merchant_id, mode, type, payload) VALUES (?, ?, ?, ?, ?::jsonb)
                """, eventId, merchantId, mode, type, payloadJson);
    }
}
