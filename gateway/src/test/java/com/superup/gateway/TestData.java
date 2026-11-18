package com.superup.gateway;

import com.superup.gateway.common.Ids;
import com.superup.gateway.gateway.ApiKeyAuthFilter;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Creates fresh clients, keys and providers for each test. Payment rows can never be deleted,
 * so every test uses its own new client instead of cleaning up.
 */
public final class TestData {

    public static final String RETURN_URL = "https://shop.example.com/payment/return";
    public static final String WEBHOOK_URL = "https://shop.example.com/api/payment-webhook";

    public record Client(String merchantId, String testKey, String liveKey) {
    }

    private TestData() {
    }

    public static Client client(JdbcTemplate db, String status) {
        String merchantId = Ids.newId("m_");
        db.update("INSERT INTO merchants (id, legal_name, trade_name, status) VALUES (?, 'Abebe Shop PLC', 'Abebe Shop', ?)",
                merchantId, status);
        for (String mode : new String[] {"test", "live"}) {
            db.update("""
                    INSERT INTO merchant_settings (merchant_id, mode, limit_per_payment, return_url_allowlist, webhook_url)
                    VALUES (?, ?, 5000000, ARRAY[?], ?)
                    """, merchantId, mode, RETURN_URL, WEBHOOK_URL);   // limit 50,000.00 ETB
        }
        return new Client(merchantId, key(db, merchantId, "test"), key(db, merchantId, "live"));
    }

    public static String key(JdbcTemplate db, String merchantId, String mode) {
        String key = "sk_" + mode + "_" + Ids.random(32);
        db.update("INSERT INTO api_keys (id, merchant_id, mode, type, key_hash, last4) VALUES (?, ?, ?, 'secret', ?, ?)",
                Ids.newId("key_"), merchantId, mode, ApiKeyAuthFilter.sha256(key), key.substring(key.length() - 4));
        return key;
    }

    /** A provider with its own code, so ledger balances in a test start from zero. */
    public static String provider(JdbcTemplate db) {
        String id = Ids.newId("prov_");
        db.update("INSERT INTO providers (id, code, name_en, type, connector, status) VALUES (?, ?, 'Test wallet', 'wallet', 'test', 'TESTING')",
                id, "TEST_" + id.substring(5).toUpperCase());
        return id;
    }

    public static String providerCode(JdbcTemplate db, String providerId) {
        return db.queryForObject("SELECT code FROM providers WHERE id = ?", String.class, providerId);
    }
}
