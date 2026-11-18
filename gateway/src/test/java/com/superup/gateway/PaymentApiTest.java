package com.superup.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superup.gateway.common.Ids;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** The Payment API through all of layer 1: key, rate limits, request checks and idempotency. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentApiTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate db;
    @Autowired
    ObjectMapper json;

    private static String body(String orderReference, String amount) {
        return """
                {"orderReference":"%s","amount":"%s","currency":"ETB","returnUrl":"%s","metadata":{"cart":"7"}}
                """.formatted(orderReference, amount, TestData.RETURN_URL);
    }

    private ResultActions create(String key, String idempotencyKey, String body) throws Exception {
        var request = post("/api/v1/payment-sessions").contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            request.header("Authorization", "Bearer " + key);
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request);
    }

    @Test
    void createsAPaymentAndReadsItBack() throws Exception {
        TestData.Client client = TestData.client(db, "KYB_REVIEW");
        String answer = create(client.testKey(), Ids.random(10), body("ORDER_10001", "1500.00"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(header().string("RateLimit-Limit", "200"))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.mode").value("test"))
                .andExpect(jsonPath("$.amount").value("1500.00"))
                .andExpect(jsonPath("$.amountRefunded").value("0.00"))
                .andExpect(jsonPath("$.fee").isEmpty())
                .andExpect(jsonPath("$.metadata.cart").value("7"))
                .andReturn().getResponse().getContentAsString();
        JsonNode session = json.readTree(answer);
        String id = session.get("id").asText();
        assertThat(id).startsWith("ps_");
        assertThat(session.get("checkoutUrl").asText()).isEqualTo("https://checkout.test/pay/" + id);

        mvc.perform(get("/api/v1/payment-sessions/" + id).header("Authorization", "Bearer " + client.testKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("CREATED"));

        mvc.perform(get("/api/v1/payments/verify/ORDER_10001").header("Authorization", "Bearer " + client.testKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));

        Integer audits = db.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE object_id = ? AND action = 'payment_session.created' AND correlation_id LIKE 'req_%'",
                Integer.class, id);
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void refusesMissingWrongAndPublicKeys() throws Exception {
        create(null, "k1", body("O1", "10.00"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.type").value("authentication_failed"))
                .andExpect(jsonPath("$.error.requestId").exists());
        create("sk_test_doesnotexist", "k1", body("O1", "10.00")).andExpect(status().isUnauthorized());
        create("pk_test_abc", "k1", body("O1", "10.00")).andExpect(status().isUnauthorized());
    }

    @Test
    void aTestKeyCannotPretendToBeLive() throws Exception {
        TestData.Client client = TestData.client(db, "LIVE");
        String mislabelled = "sk_live_" + client.testKey().substring("sk_test_".length());
        create(mislabelled, "k1", body("O1", "10.00")).andExpect(status().isUnauthorized());
    }

    @Test
    void liveKeysNeedAnApprovedClient() throws Exception {
        TestData.Client waiting = TestData.client(db, "KYB_REVIEW");
        create(waiting.liveKey(), "k1", body("O1", "10.00"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("merchant_not_live"));

        TestData.Client live = TestData.client(db, "LIVE");
        create(live.liveKey(), "k1", body("O1", "10.00"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("live"));
    }

    @Test
    void aSuspendedClientCanReadButNotCreate() throws Exception {
        TestData.Client client = TestData.client(db, "LIVE");
        String id = json.readTree(create(client.testKey(), "k1", body("O1", "10.00"))
                .andReturn().getResponse().getContentAsString()).get("id").asText();
        db.update("UPDATE merchants SET status = 'SUSPENDED' WHERE id = ?", client.merchantId());

        create(client.testKey(), "k2", body("O2", "10.00"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("merchant_suspended"));
        mvc.perform(get("/api/v1/payment-sessions/" + id).header("Authorization", "Bearer " + client.testKey()))
                .andExpect(status().isOk());
    }

    @Test
    void testAndLiveNeverMix() throws Exception {
        TestData.Client client = TestData.client(db, "LIVE");
        String id = json.readTree(create(client.testKey(), "k1", body("O1", "10.00"))
                .andReturn().getResponse().getContentAsString()).get("id").asText();
        mvc.perform(get("/api/v1/payment-sessions/" + id).header("Authorization", "Bearer " + client.liveKey()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("not_found"));

        TestData.Client other = TestData.client(db, "LIVE");
        mvc.perform(get("/api/v1/payment-sessions/" + id).header("Authorization", "Bearer " + other.testKey()))
                .andExpect(status().isNotFound());
    }

    @Test
    void aRetryWithTheSameKeyReturnsTheSamePayment() throws Exception {
        TestData.Client client = TestData.client(db, "KYB_REVIEW");
        String first = create(client.testKey(), "retry-1", body("ORDER_7", "250.00"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = create(client.testKey(), "retry-1", body("ORDER_7", "250.00"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();
        assertThat(second).isEqualTo(first);
        Integer sessions = db.queryForObject("SELECT count(*) FROM payment_sessions WHERE merchant_id = ?",
                Integer.class, client.merchantId());
        assertThat(sessions).isEqualTo(1);

        create(client.testKey(), "retry-1", body("ORDER_7", "999.00"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.type").value("idempotency_conflict"));
        create(client.testKey(), null, body("ORDER_8", "1.00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("idempotency_key_missing"));
    }

    @Test
    void checksTheRequest() throws Exception {
        TestData.Client client = TestData.client(db, "KYB_REVIEW");
        String key = client.testKey();

        create(key, Ids.random(8), """
                {"orderReference":"O1","amount":1500.00,"currency":"ETB","returnUrl":"%s"}
                """.formatted(TestData.RETURN_URL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("amount_invalid"))
                .andExpect(jsonPath("$.error.param").value("amount"));
        create(key, Ids.random(8), body("O1", "15.5")).andExpect(jsonPath("$.error.code").value("amount_invalid"));
        create(key, Ids.random(8), body("O1", "0.00")).andExpect(jsonPath("$.error.code").value("amount_invalid"));
        create(key, Ids.random(8), body("O1", "50000.01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("amount_over_limit"));
        create(key, Ids.random(8), """
                {"orderReference":"O1","amount":"10.00","currency":"USD","returnUrl":"%s"}
                """.formatted(TestData.RETURN_URL)).andExpect(jsonPath("$.error.code").value("currency_invalid"));
        create(key, Ids.random(8), """
                {"orderReference":"O1","amount":"10.00","currency":"ETB","returnUrl":"https://evil.example.com/"}
                """).andExpect(jsonPath("$.error.code").value("return_url_not_registered"));
        create(key, Ids.random(8), """
                {"orderReference":"O1","amount":"10.00","currency":"ETB","returnUrl":"%s","status":"SUCCEEDED"}
                """.formatted(TestData.RETURN_URL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("parameter_unknown"))
                .andExpect(jsonPath("$.error.param").value("status"));
        create(key, Ids.random(8), "{not json").andExpect(jsonPath("$.error.code").value("body_malformed"));
        create(key, Ids.random(8), """
                {"amount":"10.00","currency":"ETB","returnUrl":"%s"}
                """.formatted(TestData.RETURN_URL))
                .andExpect(jsonPath("$.error.code").value("parameter_missing"))
                .andExpect(jsonPath("$.error.param").value("orderReference"));

        Integer sessions = db.queryForObject("SELECT count(*) FROM payment_sessions WHERE merchant_id = ?",
                Integer.class, client.merchantId());
        assertThat(sessions).isZero();
    }

    @Test
    void unknownPaymentsAndRoutesAreNotFound() throws Exception {
        TestData.Client client = TestData.client(db, "KYB_REVIEW");
        mvc.perform(get("/api/v1/payment-sessions/ps_nothere").header("Authorization", "Bearer " + client.testKey()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/payments/verify/NOPE").header("Authorization", "Bearer " + client.testKey()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/nothing").header("Authorization", "Bearer " + client.testKey()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("route_missing"));
    }
}
