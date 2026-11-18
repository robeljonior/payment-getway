package com.superup.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.superup.gateway.common.Ids;
import com.superup.gateway.orchestrator.NewPayment;
import com.superup.gateway.orchestrator.PaymentService;
import com.superup.gateway.record.Ledger;
import com.superup.gateway.record.PaymentRow;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The six parts of layer 4 (docs/08-data-model.md): the view works out the status from the
 * candidates, successes and failures, and the database itself enforces the rules.
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentRecordTest {

    @Autowired
    PaymentService payments;
    @Autowired
    JdbcTemplate db;
    @Autowired
    Ledger ledger;
    @Autowired
    TransactionTemplate transaction;

    TestData.Client client;
    String provider;

    @BeforeEach
    void setUp() {
        client = TestData.client(db, "LIVE");
        provider = TestData.provider(db);
    }

    private PaymentRow newPayment(long santim) {
        NewPayment p = new NewPayment("ORDER_" + Ids.random(6), santim, "ETB", null, TestData.RETURN_URL, null, 900, "{}");
        return transaction.execute(s -> payments.create(client.merchantId(), "test", "key_test", "127.0.0.1", p));
    }

    private String status(String sessionId) {
        return db.queryForObject("SELECT status FROM payments_view WHERE session_id = ?", String.class, sessionId);
    }

    @Test
    void telebirrFailsThenTheBankSucceeds() {
        PaymentRow payment = newPayment(100_000);   // 1,000.00 ETB, as in the QR example
        String id = payment.sessionId();
        assertThat(payment.status()).isEqualTo("CREATED");

        String first = payments.startAttempt(id, "push", provider, "+2519****1234");
        assertThat(status(id)).isEqualTo("AUTHORIZATION_REQUIRED");
        payments.moveAttempt(first, "PROCESSING", "TB-REF-1");
        assertThat(status(id)).isEqualTo("PROCESSING");

        PaymentRow afterFailure = payments.recordTryFailure(first, "DECLINED", "insufficient_balance");
        assertThat(afterFailure.status()).isEqualTo("AUTHORIZATION_REQUIRED");   // the customer can try again
        assertThat(afterFailure.lastFailureReason()).isEqualTo("DECLINED");

        String second = payments.startAttempt(id, "otp", provider, "1000****01");
        payments.moveAttempt(second, "PENDING", "BANK-REF-2");
        assertThat(status(id)).isEqualTo("PENDING");

        PaymentRow paid = payments.recordSuccess(second, null, "status_check");
        assertThat(paid.status()).isEqualTo("SUCCEEDED");
        assertThat(paid.fee()).isEqualTo(1_500L);    // 1.5% = 15.00 ETB
        assertThat(paid.net()).isEqualTo(98_500L);   // 985.00 ETB for the client
        assertThat(paid.tries()).isEqualTo(2);
        assertThat(paid.paidMethod()).isEqualTo("otp");

        // Journal J1: the provider owes 1,000, the client is owed 985, our fee is 15.
        String code = TestData.providerCode(db, provider);
        assertThat(ledger.balance("provider_receivable:" + code, "test")).isEqualTo(100_000L);
        assertThat(ledger.balance("merchant:" + client.merchantId() + ":pending", "test")).isEqualTo(-98_500L);

        // The success, the journal and the event were written together.
        assertThat(db.queryForObject("SELECT count(*) FROM outbox_events WHERE type = 'payment.succeeded' AND payload->'data'->'object'->>'id' = ?",
                Integer.class, id)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT provider_ref FROM payment_successes WHERE session_id = ?", String.class, id))
                .isEqualTo("BANK-REF-2");
    }

    @Test
    void aPaymentIsPaidOnlyOnce() {
        String id = newPayment(5_000).sessionId();
        String a = payments.startAttempt(id, "push", provider, null);
        String b = payments.startAttempt(id, "otp", provider, null);
        payments.recordSuccess(a, "R1", "status_check");
        assertThatThrownBy(() -> payments.recordSuccess(b, "R2", "status_check")).isInstanceOf(DataAccessException.class);
        assertThat(db.queryForObject("SELECT count(*) FROM payment_successes WHERE session_id = ?", Integer.class, id))
                .isEqualTo(1);
        // The failed second post left no journal behind.
        assertThat(db.queryForObject("SELECT count(*) FROM ledger_journals WHERE source_event_id = ?", Integer.class,
                "payment_success:" + id)).isEqualTo(1);
    }

    @Test
    void resultsCanNeverBeChanged() {
        String id = newPayment(5_000).sessionId();
        String a = payments.startAttempt(id, "push", provider, null);
        payments.recordSuccess(a, "R1", "status_check");

        assertThatThrownBy(() -> db.update("UPDATE payment_successes SET fee = 0 WHERE session_id = ?", id))
                .hasMessageContaining("can never be changed");
        assertThatThrownBy(() -> db.update("DELETE FROM payment_successes WHERE session_id = ?", id))
                .hasMessageContaining("can never be changed");
        assertThatThrownBy(() -> db.update("UPDATE payment_sessions SET amount = 1 WHERE id = ?", id))
                .hasMessageContaining("only have its customer hint wiped");
        assertThatThrownBy(() -> payments.moveAttempt(a, "PENDING", null)).hasMessageContaining("locked");
        assertThatThrownBy(() -> db.update("DELETE FROM audit_logs")).hasMessageContaining("can never be changed");
    }

    @Test
    void aFailedTryCanOnlySucceedThroughAnApprovedCorrection() {
        String id = newPayment(5_000).sessionId();
        String a = payments.startAttempt(id, "push", provider, null);
        payments.recordTryFailure(a, "NOT_APPROVED", null);
        assertThatThrownBy(() -> payments.recordSuccess(a, "R1", "reconciliation"))
                .hasMessageContaining("only an approved correction");

        String failureId = db.queryForObject("SELECT id FROM payment_failures WHERE attempt_id = ?", String.class, a);
        PaymentRow corrected = payments.recordSuccess(a, "R1", "reconciliation", failureId, "appr_1");
        assertThat(corrected.status()).isEqualTo("SUCCEEDED");
        assertThat(db.queryForObject("SELECT count(*) FROM payment_failures WHERE id = ?", Integer.class, failureId))
                .isEqualTo(1);   // the failure row stays as it was
    }

    @Test
    void noExpiryWhileMoneyMayBeMoving() {
        String id = newPayment(5_000).sessionId();
        String a = payments.startAttempt(id, "push", provider, null);
        payments.moveAttempt(a, "PENDING", "R1");
        assertThatThrownBy(() -> payments.endPayment(id, "EXPIRED")).hasMessageContaining("try still running");

        payments.recordTryFailure(a, "DECLINED", null);
        PaymentRow ended = payments.endPayment(id, "EXPIRED");
        assertThat(ended.status()).isEqualTo("EXPIRED");
        assertThat(db.queryForObject("SELECT count(*) FROM outbox_events WHERE type = 'payment.expired' AND payload->'data'->'object'->>'id' = ?",
                Integer.class, id)).isEqualTo(1);

        // An ended payment takes no new tries, and even a try written directly can't be paid.
        assertThatThrownBy(() -> payments.startAttempt(id, "otp", provider, null))
                .hasMessageContaining("can't start a new try");
        db.update("INSERT INTO payment_attempts (id, session_id, mode, method, provider_id) VALUES ('att_late_' || ?, ?, 'test', 'otp', ?)",
                id, id, provider);
        assertThatThrownBy(() -> payments.recordSuccess("att_late_" + id, "R2", "status_check"))
                .hasMessageContaining("already ended");
    }

    @Test
    void aPaidPaymentCannotBeCancelledAndCancelledMeansFailed() {
        String paid = newPayment(5_000).sessionId();
        payments.recordSuccess(payments.startAttempt(paid, "push", provider, null), "R1", "status_check");
        assertThatThrownBy(() -> payments.endPayment(paid, "CANCELLED")).hasMessageContaining("already paid");

        String cancelled = newPayment(5_000).sessionId();
        assertThat(payments.endPayment(cancelled, "CANCELLED").status()).isEqualTo("FAILED");
    }

    @Test
    void theSuccessAmountMustMatchAndStepsOnlyMoveForward() {
        String id = newPayment(5_000).sessionId();
        String a = payments.startAttempt(id, "push", provider, null);
        payments.moveAttempt(a, "PROCESSING", "R1");
        assertThatThrownBy(() -> payments.moveAttempt(a, "ACTION_REQUIRED", null)).hasMessageContaining("only move forward");
        assertThatThrownBy(() -> db.update("UPDATE payment_attempts SET provider_ref = 'OTHER-REF' WHERE id = ?", a))
                .hasMessageContaining("set once");

        String journal = transaction.execute(s -> ledger.post("test", "test:" + Ids.random(8), "test",
                List.of(Ledger.Line.debit("a:" + Ids.random(4), "asset", 4_999), Ledger.Line.credit("b:" + Ids.random(4), "liability", 4_999))));
        assertThatThrownBy(() -> db.update("""
                INSERT INTO payment_successes (id, session_id, attempt_id, mode, amount, fee, net, confirmed_by, journal_id)
                VALUES ('suc_x1', ?, ?, 'test', 4999, 0, 4999, 'status_check', ?)
                """, id, a, journal)).hasMessageContaining("is not the session amount");
    }

    @Test
    void anUnbalancedJournalIsRefused() {
        assertThatThrownBy(() -> transaction.execute(s -> ledger.post("test", "test:" + Ids.random(8), "test",
                List.of(Ledger.Line.debit("a:" + Ids.random(4), "asset", 1_000),
                        Ledger.Line.credit("b:" + Ids.random(4), "liability", 999)))))
                .hasMessageContaining("not balanced");
    }

    @Test
    void theExpiryJobQueryFindsOnlyPaymentsWithNothingRunning() {
        String idle = newPayment(5_000).sessionId();
        String running = newPayment(5_000).sessionId();
        payments.startAttempt(running, "push", provider, null);
        // Pretend both payments are old. Sessions can't be changed, so the guard is switched off for this setup only.
        transaction.executeWithoutResult(s -> {
            db.execute("ALTER TABLE payment_sessions DISABLE TRIGGER payment_sessions_guard");
            db.update("UPDATE payment_sessions SET expires_at = now() - interval '1 minute' WHERE id IN (?, ?)",
                    idle, running);
            db.execute("ALTER TABLE payment_sessions ENABLE TRIGGER payment_sessions_guard");
        });
        List<String> due = payments.dueForExpiry(10_000);
        assertThat(due).contains(idle);
        assertThat(due).doesNotContain(running);
    }
}
