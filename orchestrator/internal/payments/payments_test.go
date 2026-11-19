package payments_test

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"testing"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/robeljonior/payment-getway/orchestrator/internal/expiry"
	"github.com/robeljonior/payment-getway/orchestrator/internal/payments"
	"github.com/robeljonior/payment-getway/orchestrator/internal/testdb"
)

type fixture struct {
	t        *testing.T
	ctx      context.Context
	db       *pgxpool.Pool
	svc      *payments.Service
	merchant string
	provider string
}

func setup(t *testing.T) *fixture {
	db := testdb.Open(t)
	svc := payments.NewService(db, 150, "https://pay.example.com/checkout")
	return &fixture{t: t, ctx: context.Background(), db: db, svc: svc, merchant: testdb.MerchantID(),
		provider: testdb.Provider(t, svc, "TESTING")}
}

func (f *fixture) newPayment(santim int64) payments.Payment {
	f.t.Helper()
	p, created, err := f.svc.Create(f.ctx, payments.NewPayment{
		CreateKey: "ck_" + payments.RandomText(20), MerchantID: f.merchant, Mode: "test",
		OrderReference: "ORDER_" + payments.RandomText(6), Amount: santim, Currency: "ETB",
		ReturnURL: "https://shop.example.com/payment/return", ExpiresInSeconds: 900,
	})
	if err != nil || !created {
		f.t.Fatalf("create: %v (created %v)", err, created)
	}
	return p
}

func (f *fixture) start(sessionID, method string) string {
	f.t.Helper()
	a, err := f.svc.StartAttempt(f.ctx, sessionID, method, f.provider, nil)
	if err != nil {
		f.t.Fatalf("start attempt: %v", err)
	}
	return a.ID
}

func (f *fixture) status(sessionID string) string {
	f.t.Helper()
	var s string
	if err := f.db.QueryRow(f.ctx, "SELECT status FROM payments_view WHERE session_id = $1", sessionID).Scan(&s); err != nil {
		f.t.Fatal(err)
	}
	return s
}

func (f *fixture) count(sql string, args ...any) int {
	f.t.Helper()
	var n int
	if err := f.db.QueryRow(f.ctx, sql, args...).Scan(&n); err != nil {
		f.t.Fatal(err)
	}
	return n
}

func ref(s string) *string { return &s }

func mustFail(t *testing.T, err error, contains string) {
	t.Helper()
	if err == nil {
		t.Fatalf("expected an error containing %q, got none", contains)
	}
	if !strings.Contains(err.Error(), contains) {
		t.Fatalf("expected an error containing %q, got %v", contains, err)
	}
}

func equal[T comparable](t *testing.T, what string, got, want T) {
	t.Helper()
	if got != want {
		t.Fatalf("%s: got %v, want %v", what, got, want)
	}
}

func TestTelebirrFailsThenTheBankSucceeds(t *testing.T) {
	f := setup(t)
	p := f.newPayment(100_000) // 1,000.00 ETB, as in the QR example
	id := p.SessionID
	equal(t, "status", p.Status, "CREATED")

	first := f.start(id, "push")
	equal(t, "status", f.status(id), "AUTHORIZATION_REQUIRED")
	if _, err := f.svc.MoveAttempt(f.ctx, first, "PROCESSING", ref("TB-REF-1")); err != nil {
		t.Fatal(err)
	}
	equal(t, "status", f.status(id), "PROCESSING")

	afterFailure, err := f.svc.RecordTryFailure(f.ctx, first, "DECLINED", ref("insufficient_balance"))
	if err != nil {
		t.Fatal(err)
	}
	equal(t, "status after a failed try", afterFailure.Status, "AUTHORIZATION_REQUIRED") // the customer can try again
	equal(t, "last failure", *afterFailure.LastFailureReason, "DECLINED")

	second := f.start(id, "otp")
	if _, err := f.svc.MoveAttempt(f.ctx, second, "PENDING", ref("BANK-REF-2")); err != nil {
		t.Fatal(err)
	}
	equal(t, "status", f.status(id), "PENDING")

	paid, err := f.svc.RecordSuccess(f.ctx, second, nil, "status_check", nil)
	if err != nil {
		t.Fatal(err)
	}
	equal(t, "status", paid.Status, "SUCCEEDED")
	equal(t, "fee", *paid.Fee, 1_500)  // 1.5% = 15.00 ETB
	equal(t, "net", *paid.Net, 98_500) // 985.00 ETB for the client
	equal(t, "tries", paid.Tries, 2)
	equal(t, "paid method", *paid.PaidMethod, "otp")

	view := f.svc.View(paid)
	equal(t, "API amount", view.Amount, "1000.00")
	equal(t, "API fee", *view.Fee, "15.00")
	equal(t, "API method", view.PaymentMethod.Type, "bank")
	equal(t, "checkout URL", view.CheckoutURL, "https://pay.example.com/checkout/"+id)

	// Journal J1: the provider owes 1,000, the client is owed 985, our fee is 15.
	owed, _ := f.svc.Balance(f.ctx, "provider_receivable:"+f.provider, "test")
	equal(t, "provider receivable", owed, 100_000)
	client, _ := f.svc.Balance(f.ctx, "merchant:"+f.merchant+":pending", "test")
	equal(t, "client pending", client, -98_500)

	// The success, the journal and the event were written together.
	equal(t, "succeeded events", f.count(`SELECT count(*) FROM outbox_events
		WHERE type = 'payment.succeeded' AND payload->'data'->'object'->>'id' = $1`, id), 1)
	var bankRef string
	_ = f.db.QueryRow(f.ctx, "SELECT provider_ref FROM payment_successes WHERE session_id = $1", id).Scan(&bankRef)
	equal(t, "bank reference", bankRef, "BANK-REF-2")
}

func TestARetriedCreateNeverMakesTwoPayments(t *testing.T) {
	f := setup(t)
	n := payments.NewPayment{CreateKey: "ck_" + payments.RandomText(20), MerchantID: f.merchant, Mode: "test",
		OrderReference: "ORDER_1", Amount: 5_000, Currency: "ETB", ReturnURL: "https://shop.example.com/r",
		ExpiresInSeconds: 900, Metadata: json.RawMessage(`{"cart":"42"}`)}
	a, created, err := f.svc.Create(f.ctx, n)
	if err != nil || !created {
		t.Fatalf("first create: %v %v", err, created)
	}
	b, created, err := f.svc.Create(f.ctx, n)
	if err != nil || created {
		t.Fatalf("second create: %v %v", err, created)
	}
	equal(t, "same payment", b.SessionID, a.SessionID)
	equal(t, "metadata", string(b.Metadata), `{"cart": "42"}`)

	n.MerchantID = testdb.MerchantID() // another client can't use or see it
	_, _, err = f.svc.Create(f.ctx, n)
	mustFail(t, err, "create_key_in_use")
}

func TestAPaymentIsPaidOnlyOnce(t *testing.T) {
	f := setup(t)
	id := f.newPayment(5_000).SessionID
	a, b := f.start(id, "push"), f.start(id, "otp")
	if _, err := f.svc.RecordSuccess(f.ctx, a, ref("R1"), "status_check", nil); err != nil {
		t.Fatal(err)
	}
	_, err := f.svc.RecordSuccess(f.ctx, b, ref("R2"), "status_check", nil)
	if err == nil {
		t.Fatal("a second success was accepted")
	}
	equal(t, "successes", f.count("SELECT count(*) FROM payment_successes WHERE session_id = $1", id), 1)
	// The refused second post left no journal behind.
	equal(t, "journals", f.count("SELECT count(*) FROM ledger_journals WHERE source_event_id = $1", "payment_success:"+id), 1)
}

func TestResultsCanNeverBeChanged(t *testing.T) {
	f := setup(t)
	id := f.newPayment(5_000).SessionID
	a := f.start(id, "push")
	if _, err := f.svc.RecordSuccess(f.ctx, a, ref("R1"), "status_check", nil); err != nil {
		t.Fatal(err)
	}
	_, err := f.db.Exec(f.ctx, "UPDATE payment_successes SET fee = 0 WHERE session_id = $1", id)
	mustFail(t, err, "can never be changed")
	_, err = f.db.Exec(f.ctx, "DELETE FROM payment_successes WHERE session_id = $1", id)
	mustFail(t, err, "can never be changed")
	_, err = f.db.Exec(f.ctx, "UPDATE payment_sessions SET amount = 1 WHERE id = $1", id)
	mustFail(t, err, "only have its customer hint wiped")
	_, err = f.svc.MoveAttempt(f.ctx, a, "PENDING", nil)
	mustFail(t, err, "locked")
	_, err = f.db.Exec(f.ctx, "DELETE FROM audit_logs")
	mustFail(t, err, "can never be changed")
}

func TestAFailedTryCanOnlySucceedThroughAnApprovedCorrection(t *testing.T) {
	f := setup(t)
	id := f.newPayment(5_000).SessionID
	a := f.start(id, "push")
	if _, err := f.svc.RecordTryFailure(f.ctx, a, "NOT_APPROVED", nil); err != nil {
		t.Fatal(err)
	}
	_, err := f.svc.RecordSuccess(f.ctx, a, ref("R1"), "reconciliation", nil)
	mustFail(t, err, "only an approved correction")

	var failureID string
	_ = f.db.QueryRow(f.ctx, "SELECT id FROM payment_failures WHERE attempt_id = $1", a).Scan(&failureID)
	fixed, err := f.svc.RecordSuccess(f.ctx, a, ref("R1"), "reconciliation",
		&payments.Correction{FailureID: failureID, ApprovalID: "appr_1"})
	if err != nil {
		t.Fatal(err)
	}
	equal(t, "status", fixed.Status, "SUCCEEDED")
	equal(t, "the failure row stays", f.count("SELECT count(*) FROM payment_failures WHERE id = $1", failureID), 1)
}

func TestNoExpiryWhileMoneyMayBeMoving(t *testing.T) {
	f := setup(t)
	id := f.newPayment(5_000).SessionID
	a := f.start(id, "push")
	if _, err := f.svc.MoveAttempt(f.ctx, a, "PENDING", ref("R1")); err != nil {
		t.Fatal(err)
	}
	_, err := f.svc.EndPayment(f.ctx, id, "EXPIRED")
	mustFail(t, err, "try still running")

	if _, err := f.svc.RecordTryFailure(f.ctx, a, "DECLINED", nil); err != nil {
		t.Fatal(err)
	}
	ended, err := f.svc.EndPayment(f.ctx, id, "EXPIRED")
	if err != nil {
		t.Fatal(err)
	}
	equal(t, "status", ended.Status, "EXPIRED")
	equal(t, "expired events", f.count(`SELECT count(*) FROM outbox_events
		WHERE type = 'payment.expired' AND payload->'data'->'object'->>'id' = $1`, id), 1)

	// An ended payment takes no new tries, and even a try written directly can't be paid.
	_, err = f.svc.StartAttempt(f.ctx, id, "otp", f.provider, nil)
	mustFail(t, err, "can't start a new try")
	var e *payments.Error
	if !errors.As(err, &e) || e.Kind != payments.KindConflict {
		t.Fatalf("want a conflict error, got %v", err)
	}
	if _, err := f.db.Exec(f.ctx, `INSERT INTO payment_attempts (id, session_id, mode, method, provider_id)
		VALUES ('att_late_' || $1, $1, 'test', 'otp', (SELECT id FROM providers WHERE code = $2))`, id, f.provider); err != nil {
		t.Fatal(err)
	}
	_, err = f.svc.RecordSuccess(f.ctx, "att_late_"+id, ref("R2"), "status_check", nil)
	mustFail(t, err, "already ended")
}

func TestAPaidPaymentCannotBeCancelledAndCancelledMeansFailed(t *testing.T) {
	f := setup(t)
	paid := f.newPayment(5_000).SessionID
	if _, err := f.svc.RecordSuccess(f.ctx, f.start(paid, "push"), ref("R1"), "status_check", nil); err != nil {
		t.Fatal(err)
	}
	_, err := f.svc.EndPayment(f.ctx, paid, "CANCELLED")
	mustFail(t, err, "already paid")

	cancelled, err := f.svc.EndPayment(f.ctx, f.newPayment(5_000).SessionID, "CANCELLED")
	if err != nil {
		t.Fatal(err)
	}
	equal(t, "status", cancelled.Status, "FAILED")
	equal(t, "failure code", f.svc.View(cancelled).Failure.Code, "cancelled")
}

func TestStepsOnlyMoveForwardAndTheSuccessAmountMustMatch(t *testing.T) {
	f := setup(t)
	id := f.newPayment(5_000).SessionID
	a := f.start(id, "push")
	if _, err := f.svc.MoveAttempt(f.ctx, a, "PROCESSING", ref("R1")); err != nil {
		t.Fatal(err)
	}
	_, err := f.svc.MoveAttempt(f.ctx, a, "ACTION_REQUIRED", nil)
	mustFail(t, err, "only move forward")
	_, err = f.db.Exec(f.ctx, "UPDATE payment_attempts SET provider_ref = 'OTHER-REF' WHERE id = $1", a)
	mustFail(t, err, "set once")

	tx, err := f.db.Begin(f.ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback(f.ctx)
	journal, err := payments.PostJournal(f.ctx, tx, "test", "test:"+payments.RandomText(8), "test", []payments.Line{
		payments.Debit("a:"+payments.RandomText(4), "asset", 4_999),
		payments.Credit("b:"+payments.RandomText(4), "liability", 4_999),
	})
	if err != nil {
		t.Fatal(err)
	}
	_, err = tx.Exec(f.ctx, `INSERT INTO payment_successes (id, session_id, attempt_id, mode, amount, fee, net, confirmed_by, journal_id)
		VALUES ('suc_x1', $1, $2, 'test', 4999, 0, 4999, 'status_check', $3)`, id, a, journal)
	mustFail(t, err, "is not the session amount")
}

func TestAnUnbalancedJournalIsRefused(t *testing.T) {
	f := setup(t)
	tx, err := f.db.Begin(f.ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback(f.ctx)
	if _, err := payments.PostJournal(f.ctx, tx, "test", "test:"+payments.RandomText(8), "test", []payments.Line{
		payments.Debit("a:"+payments.RandomText(4), "asset", 1_000),
		payments.Credit("b:"+payments.RandomText(4), "liability", 999),
	}); err != nil {
		t.Fatal(err)
	}
	mustFail(t, tx.Commit(f.ctx), "not balanced") // checked when the transaction commits
}

func TestTheExpiryJobEndsOnlyPaymentsWithNothingRunning(t *testing.T) {
	f := setup(t)
	idle := f.newPayment(5_000).SessionID
	running := f.newPayment(5_000).SessionID
	f.start(running, "push")
	// Pretend both payments are old. Sessions can't be changed, so the guard is switched off for this setup only.
	tx, err := f.db.Begin(f.ctx)
	if err != nil {
		t.Fatal(err)
	}
	for _, sql := range []string{
		"ALTER TABLE payment_sessions DISABLE TRIGGER payment_sessions_guard",
		"UPDATE payment_sessions SET expires_at = now() - interval '1 minute' WHERE id IN ('" + idle + "', '" + running + "')",
		"ALTER TABLE payment_sessions ENABLE TRIGGER payment_sessions_guard",
	} {
		if _, err := tx.Exec(f.ctx, sql); err != nil {
			t.Fatal(err)
		}
	}
	if err := tx.Commit(f.ctx); err != nil {
		t.Fatal(err)
	}

	due, err := f.svc.DueForExpiry(f.ctx, 10_000)
	if err != nil {
		t.Fatal(err)
	}
	has := map[string]bool{}
	for _, id := range due {
		has[id] = true
	}
	if !has[idle] || has[running] {
		t.Fatalf("due list is wrong: idle %v, running %v", has[idle], has[running])
	}

	expiry.Once(f.ctx, f.svc, discard())
	equal(t, "idle payment", f.status(idle), "EXPIRED")
	equal(t, "running payment", f.status(running), "AUTHORIZATION_REQUIRED")
	_, err = f.svc.StartAttempt(f.ctx, running, "otp", f.provider, nil)
	mustFail(t, err, "can't start a new try") // its time is up, even though it isn't closed yet
}

func TestProvidersMustBeAvailableForTheMode(t *testing.T) {
	f := setup(t)
	id := f.newPayment(5_000).SessionID
	_, err := f.svc.StartAttempt(f.ctx, id, "push", "NO_SUCH_BANK", nil)
	mustFail(t, err, "unknown_provider")
	_, err = f.svc.StartAttempt(f.ctx, id, "push", testdb.Provider(t, f.svc, "PAUSED"), nil)
	mustFail(t, err, "provider_not_available")
	_, err = f.svc.StartAttempt(f.ctx, id, "fax", f.provider, nil)
	mustFail(t, err, "invalid_method")

	live, _, err := f.svc.Create(f.ctx, payments.NewPayment{CreateKey: "ck_" + payments.RandomText(20),
		MerchantID: f.merchant, Mode: "live", OrderReference: "ORDER_L", Amount: 5_000, Currency: "ETB",
		ReturnURL: "https://shop.example.com/r", ExpiresInSeconds: 900})
	if err != nil {
		t.Fatal(err)
	}
	_, err = f.svc.StartAttempt(f.ctx, live.SessionID, "push", f.provider, nil) // still TESTING
	mustFail(t, err, "provider_not_available")
}

func TestMoneyIsWholeSantimAndFeesRoundHalfUp(t *testing.T) {
	equal(t, "format", payments.FormatSantim(150_000), "1500.00")
	equal(t, "format", payments.FormatSantim(5), "0.05")
	equal(t, "fee", payments.Fee(100_000, 150), 1_500)
	equal(t, "fee half up", payments.Fee(100, 150), 2)   // 1.5 santim rounds up to 2
	equal(t, "fee below half", payments.Fee(33, 150), 0) // 0.495 santim rounds down
}
