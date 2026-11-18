package com.superup.gateway.record;

import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Layer 4: the audit log. Rows can never be changed or deleted. */
@Repository
public class AuditLog {

    private final JdbcTemplate db;

    public AuditLog(JdbcTemplate db) {
        this.db = db;
    }

    public void record(String actorType, String actorId, String action, String objectType, String objectId,
                       String afterJson, String ip) {
        db.update("""
                INSERT INTO audit_logs (actor_type, actor_id, action, object_type, object_id, after_data, ip, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                """, actorType, actorId, action, objectType, objectId, afterJson, ip, MDC.get("requestId"));
    }
}
