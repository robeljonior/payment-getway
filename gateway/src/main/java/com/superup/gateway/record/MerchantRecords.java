package com.superup.gateway.record;

import java.sql.Array;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Layer 4, part 5: clients (merchants) and their settings per mode. */
@Repository
public class MerchantRecords {

    public record Settings(long limitPerPayment, List<String> returnUrlAllowlist, String webhookUrl) {
    }

    private final JdbcTemplate db;

    public MerchantRecords(JdbcTemplate db) {
        this.db = db;
    }

    public Optional<Settings> settings(String merchantId, String mode) {
        return db.query("""
                SELECT limit_per_payment, return_url_allowlist, webhook_url
                  FROM merchant_settings WHERE merchant_id = ? AND mode = ?
                """, (rs, i) -> {
            Array urls = rs.getArray("return_url_allowlist");
            List<String> list = urls == null ? List.of() : Arrays.asList((String[]) urls.getArray());
            return new Settings(rs.getLong("limit_per_payment"), list, rs.getString("webhook_url"));
        }, merchantId, mode).stream().findFirst();
    }
}
