package com.superup.gateway.dev;

import com.superup.gateway.common.Ids;
import com.superup.gateway.gateway.ApiKeyAuthFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Only with the "dev" profile: creates a demo client and a fresh test API key,
 * so you can try the API on your own computer. Never runs in test or live servers.
 * (Until the Back Office is built, this is how a client gets a key. Test banks are added in the
 * orchestrator; see its README.)
 */
@Component
@Profile("dev")
public class DevDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);
    private static final String MERCHANT = "m_demo_shop";

    private final JdbcTemplate db;

    public DevDataSeeder(JdbcTemplate db) {
        this.db = db;
    }

    @Override
    public void run(ApplicationArguments args) {
        db.update("""
                INSERT INTO merchants (id, legal_name, trade_name, status) VALUES (?, 'Demo Shop PLC', 'Demo Shop', 'KYB_REVIEW')
                ON CONFLICT (id) DO NOTHING
                """, MERCHANT);
        db.update("""
                INSERT INTO merchant_settings (merchant_id, mode, limit_per_payment, return_url_allowlist, webhook_url)
                VALUES (?, 'test', 10000000, ARRAY['https://shop.example.com/payment/return'], 'https://shop.example.com/api/payment-webhook')
                ON CONFLICT DO NOTHING
                """, MERCHANT);

        String key = "sk_test_" + Ids.random(32);
        db.update("""
                INSERT INTO api_keys (id, merchant_id, mode, type, key_hash, last4) VALUES (?, ?, 'test', 'secret', ?, ?)
                """, Ids.newId("key_"), MERCHANT, ApiKeyAuthFilter.sha256(key), key.substring(key.length() - 4));

        log.info("""

                ====================================================================
                 Demo client ready (test mode only). Your new test secret key is:

                   {}

                 It is shown only once. Try:
                   curl -X POST http://localhost:8080/api/v1/payment-sessions \\
                     -H "Authorization: Bearer {}" \\
                     -H "Idempotency-Key: order-10001-try-1" \\
                     -H "Content-Type: application/json" \\
                     -d '{"orderReference":"ORDER_10001","amount":"1500.00","currency":"ETB",\
                "returnUrl":"https://shop.example.com/payment/return"}'
                ====================================================================
                """, key, key);
    }
}
