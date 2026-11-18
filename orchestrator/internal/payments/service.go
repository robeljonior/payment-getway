// Package payments is layer 2, the orchestrator: it decides what happens next for every payment.
// It is the only place that decides that a payment succeeded or failed, and it always writes the
// result, the ledger journal and the event together, in one transaction.
package payments

import (
	"context"
	"encoding/json"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type Service struct {
	db              *pgxpool.Pool
	feeBasisPoints  int64
	checkoutBaseURL string
}

func NewService(db *pgxpool.Pool, feeBasisPoints int64, checkoutBaseURL string) *Service {
	return &Service{db: db, feeBasisPoints: feeBasisPoints, checkoutBaseURL: checkoutBaseURL}
}

// View builds the API object for one payment.
func (s *Service) View(p Payment) Session { return SessionOf(p, s.checkoutBaseURL) }

func (s *Service) inTx(ctx context.Context, f func(tx pgx.Tx) error) error {
	return fromDB(pgx.BeginFunc(ctx, s.db, f))
}

// ---- Part 1: a new success candidate ----

// NewPayment is a payment request that has passed every gateway check. Amounts are in santim.
type NewPayment struct {
	CreateKey        string          `json:"createKey"` // from the client's Idempotency-Key, made unique by the gateway
	MerchantID       string          `json:"merchantId"`
	Mode             string          `json:"mode"`
	OrderReference   string          `json:"orderReference"`
	Amount           int64           `json:"amount"`
	Currency         string          `json:"currency"`
	Description      *string         `json:"description"`
	ReturnURL        string          `json:"returnUrl"`
	WebhookURL       *string         `json:"webhookUrl"`
	ExpiresInSeconds int             `json:"expiresInSeconds"`
	Metadata         json.RawMessage `json:"metadata"`
}

// Create saves a new payment. If a payment with the same create key already exists for the same client
// and mode, it is returned instead and created is false: a retried call never makes two payments.
func (s *Service) Create(ctx context.Context, n NewPayment) (p Payment, created bool, err error) {
	if err := n.check(); err != nil {
		return p, false, err
	}
	metadata := n.Metadata
	if len(metadata) == 0 || string(metadata) == "null" {
		metadata = json.RawMessage("{}")
	}
	err = s.inTx(ctx, func(tx pgx.Tx) error {
		id := newID(prefixSession)
		tag, err := tx.Exec(ctx, `
			INSERT INTO payment_sessions (id, create_key, merchant_id, mode, order_reference, amount, currency,
			                              description, return_url, webhook_url, metadata, expires_at)
			VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, now() + make_interval(secs => $12))
			ON CONFLICT (create_key) DO NOTHING`,
			id, n.CreateKey, n.MerchantID, n.Mode, n.OrderReference, n.Amount, n.Currency, n.Description,
			n.ReturnURL, n.WebhookURL, string(metadata), n.ExpiresInSeconds)
		if err != nil {
			return err
		}
		if tag.RowsAffected() == 0 {
			// Made before. Only its own client, in the same mode, may see it.
			if err := tx.QueryRow(ctx, "SELECT id FROM payment_sessions WHERE create_key = $1 AND merchant_id = $2 AND mode = $3",
				n.CreateKey, n.MerchantID, n.Mode).Scan(&id); err != nil {
				if err == pgx.ErrNoRows {
					return conflict("create_key_in_use", "This create key belongs to another payment.")
				}
				return err
			}
		} else {
			created = true
			if err := audit(ctx, tx, "payment_session.created", "payment_session", id,
				map[string]any{"amount": n.Amount, "orderReference": n.OrderReference}); err != nil {
				return err
			}
		}
		p, err = findPayment(ctx, tx, id)
		return err
	})
	return p, created, err
}

func (n NewPayment) check() error {
	switch {
	case len(n.CreateKey) < 8 || len(n.CreateKey) > 300:
		return invalid("invalid_create_key", "createKey must be 8 to 300 characters.")
	case n.MerchantID == "":
		return invalid("invalid_merchant", "merchantId is required.")
	case n.Mode != "test" && n.Mode != "live":
		return invalid("invalid_mode", "mode must be test or live.")
	case n.OrderReference == "" || len(n.OrderReference) > 100:
		return invalid("invalid_order_reference", "orderReference must be 1 to 100 characters.")
	case n.Amount <= 0 || n.Amount > MaxAmount:
		return invalid("invalid_amount", "amount must be a whole number of santim from 1 to %d.", MaxAmount)
	case n.Currency != "ETB":
		return invalid("invalid_currency", "currency must be ETB.")
	case n.ReturnURL == "":
		return invalid("invalid_return_url", "returnUrl is required.")
	case n.ExpiresInSeconds < 60 || n.ExpiresInSeconds > 7*24*60*60:
		return invalid("invalid_expiry", "expiresInSeconds must be from 60 to 604800.")
	}
	if len(n.Metadata) > 0 && string(n.Metadata) != "null" {
		var m map[string]any
		if json.Unmarshal(n.Metadata, &m) != nil {
			return invalid("invalid_metadata", "metadata must be a JSON object.")
		}
	}
	return nil
}

// Find reads one payment, only for its own client and mode.
func (s *Service) Find(ctx context.Context, merchantID, mode, sessionID string) (Payment, error) {
	p, err := scanPayment(s.db.QueryRow(ctx,
		"SELECT "+viewColumns+" FROM payments_view WHERE session_id = $1 AND merchant_id = $2 AND mode = $3",
		sessionID, merchantID, mode))
	return p, fromDB(err)
}

// FindByID reads one payment for checkout and the connectors, which work by payment ID.
func (s *Service) FindByID(ctx context.Context, sessionID string) (Payment, error) {
	return findPayment(ctx, s.db, sessionID)
}

// LatestForOrder is the newest payment for the client's own order reference.
func (s *Service) LatestForOrder(ctx context.Context, merchantID, mode, orderReference string) (Payment, error) {
	p, err := scanPayment(s.db.QueryRow(ctx, "SELECT "+viewColumns+` FROM payments_view
		WHERE merchant_id = $1 AND mode = $2 AND order_reference = $3 ORDER BY created_at DESC LIMIT 1`,
		merchantID, mode, orderReference))
	return p, fromDB(err)
}

// ---- Tries (started by checkout, moved by the connectors) ----

var methods = map[string]bool{"push": true, "otp": true, "redirect": true, "qr": true, "request_to_pay": true,
	"bank_transfer": true, "card": true}

// StartAttempt opens a new try on an open payment, with one provider.
// In live mode the provider must be ACTIVE; test mode may also use providers that are still TESTING.
func (s *Service) StartAttempt(ctx context.Context, sessionID, method, providerCode string, payerMasked *string) (Attempt, error) {
	if !methods[method] {
		return Attempt{}, invalid("invalid_method", "method %q is not known.", method)
	}
	var a Attempt
	err := s.inTx(ctx, func(tx pgx.Tx) error {
		// Lock the payment so that the expiry job and a new try can't cross.
		if _, err := tx.Exec(ctx, "SELECT 1 FROM payment_sessions WHERE id = $1 FOR UPDATE", sessionID); err != nil {
			return err
		}
		p, err := findPayment(ctx, tx, sessionID)
		if err != nil {
			return err
		}
		if (p.Status != "CREATED" && p.Status != "AUTHORIZATION_REQUIRED") || !p.ExpiresAt.After(time.Now()) {
			return conflict("payment_not_open", "Payment %s is %s and can't start a new try.", sessionID, p.Status)
		}
		var providerID, status string
		var minAmount, maxAmount *int64
		err = tx.QueryRow(ctx, "SELECT id, status, min_amount, max_amount FROM providers WHERE code = $1", providerCode).
			Scan(&providerID, &status, &minAmount, &maxAmount)
		if err == pgx.ErrNoRows {
			return invalid("unknown_provider", "Provider %q does not exist.", providerCode)
		} else if err != nil {
			return err
		}
		if !(status == "ACTIVE" || (p.Mode == "test" && status == "TESTING")) {
			return conflict("provider_not_available", "Provider %s is %s and can't take %s payments.", providerCode, status, p.Mode)
		}
		if (minAmount != nil && p.Amount < *minAmount) || (maxAmount != nil && p.Amount > *maxAmount) {
			return conflict("amount_outside_provider_limits", "Provider %s does not take this amount.", providerCode)
		}
		id := newID(prefixAttempt)
		if _, err := tx.Exec(ctx, `
			INSERT INTO payment_attempts (id, session_id, mode, method, provider_id, payer_masked)
			VALUES ($1, $2, $3, $4, $5, $6)`, id, sessionID, p.Mode, method, providerID, payerMasked); err != nil {
			return err
		}
		if err := audit(ctx, tx, "payment_attempt.started", "payment_attempt", id,
			map[string]any{"paymentId": sessionID, "method": method, "provider": providerCode}); err != nil {
			return err
		}
		a, err = findAttempt(ctx, tx, id)
		return err
	})
	return a, err
}

var steps = map[string]bool{"ACTION_REQUIRED": true, "PROCESSING": true, "PENDING": true}

// MoveAttempt moves a running try forward, for example from waiting for the PIN to waiting for the bank.
// The bank's reference can be set once.
func (s *Service) MoveAttempt(ctx context.Context, attemptID, step string, providerRef *string) (Attempt, error) {
	if !steps[step] {
		return Attempt{}, invalid("invalid_step", "step must be ACTION_REQUIRED, PROCESSING or PENDING.")
	}
	var a Attempt
	err := s.inTx(ctx, func(tx pgx.Tx) error {
		tag, err := tx.Exec(ctx,
			"UPDATE payment_attempts SET step = $1, provider_ref = coalesce(provider_ref, $2) WHERE id = $3",
			step, providerRef, attemptID)
		if err != nil {
			return err
		}
		if tag.RowsAffected() == 0 {
			return ErrNotFound
		}
		a, err = findAttempt(ctx, tx, attemptID)
		return err
	})
	return a, err
}

func (s *Service) FindAttempt(ctx context.Context, attemptID string) (Attempt, error) {
	return findAttempt(ctx, s.db, attemptID)
}

// ---- Part 2: a success ----

// Correction is a late success that reconciliation found after the try was recorded as failed.
// It needs a two-person approval, and the failure row stays as it was.
type Correction struct {
	FailureID  string
	ApprovalID string
}

var confirmations = map[string]bool{"status_check": true, "reconciliation": true}

// RecordSuccess is called when the bank or wallet confirmed the money. It writes the success, ledger
// journal J1 and the payment.succeeded event together, or nothing at all.
func (s *Service) RecordSuccess(ctx context.Context, attemptID string, providerRef *string, confirmedBy string, fix *Correction) (Payment, error) {
	if !confirmations[confirmedBy] {
		return Payment{}, invalid("invalid_confirmed_by", "confirmedBy must be status_check or reconciliation.")
	}
	var after Payment
	err := s.inTx(ctx, func(tx pgx.Tx) error {
		a, err := findAttempt(ctx, tx, attemptID)
		if err != nil {
			return err
		}
		before, err := findPayment(ctx, tx, a.SessionID)
		if err != nil {
			return err
		}
		fee := Fee(before.Amount, s.feeBasisPoints)
		net := before.Amount - fee

		// Journal J1: the provider owes us the money, the client is owed the net amount, and the fee is our revenue.
		journalID, err := PostJournal(ctx, tx, "payment_success", "payment_success:"+before.SessionID, before.Mode, []Line{
			Debit("provider_receivable:"+a.ProviderCode, "asset", before.Amount),
			Credit("merchant:"+before.MerchantID+":pending", "liability", net),
			Credit("fees:gateway", "revenue", fee),
		})
		if err != nil {
			return err
		}
		ref := providerRef
		if ref == nil {
			ref = a.ProviderRef
		}
		var failureID, approvalID *string
		if fix != nil {
			failureID, approvalID = &fix.FailureID, &fix.ApprovalID
		}
		if _, err := tx.Exec(ctx, `
			INSERT INTO payment_successes (id, session_id, attempt_id, mode, amount, fee, net, provider_ref,
			                               confirmed_by, journal_id, corrects_failure_id, approval_id)
			VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)`,
			newID(prefixSuccess), before.SessionID, attemptID, before.Mode, before.Amount, fee, net, ref,
			confirmedBy, journalID, failureID, approvalID); err != nil {
			return err
		}
		if after, err = findPayment(ctx, tx, before.SessionID); err != nil {
			return err
		}
		if err := s.event(ctx, tx, after, "payment.succeeded"); err != nil {
			return err
		}
		return audit(ctx, tx, "payment.succeeded", "payment_session", after.SessionID,
			map[string]any{"attemptId": attemptID, "journalId": journalID, "fee": fee, "net": net})
	})
	return after, err
}

// ---- Part 3: failures ----

var tryFailures = map[string]bool{"DECLINED": true, "OTP_FAILED": true, "NOT_APPROVED": true, "AMOUNT_MISMATCH": true}

// RecordTryFailure records that one try failed. The payment stays open, so the customer can try another way.
func (s *Service) RecordTryFailure(ctx context.Context, attemptID, reason string, providerCode *string) (Payment, error) {
	if !tryFailures[reason] {
		return Payment{}, invalid("invalid_reason", "reason must be DECLINED, OTP_FAILED, NOT_APPROVED or AMOUNT_MISMATCH.")
	}
	var after Payment
	err := s.inTx(ctx, func(tx pgx.Tx) error {
		a, err := findAttempt(ctx, tx, attemptID)
		if err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `
			INSERT INTO payment_failures (id, session_id, attempt_id, mode, reason, provider_code)
			VALUES ($1, $2, $3, $4, $5, $6)`,
			newID(prefixFailure), a.SessionID, attemptID, a.Mode, reason, providerCode); err != nil {
			return err
		}
		if err := audit(ctx, tx, "payment_attempt.failed", "payment_attempt", attemptID,
			map[string]any{"reason": reason}); err != nil {
			return err
		}
		after, err = findPayment(ctx, tx, a.SessionID)
		return err
	})
	return after, err
}

// EndPayment ends the whole payment without money: EXPIRED (time ran out) or CANCELLED (the customer
// cancelled). The database refuses it while a try is still running, because money may be moving.
func (s *Service) EndPayment(ctx context.Context, sessionID, reason string) (Payment, error) {
	if reason != "EXPIRED" && reason != "CANCELLED" {
		return Payment{}, invalid("invalid_reason", "reason must be EXPIRED or CANCELLED.")
	}
	var after Payment
	err := s.inTx(ctx, func(tx pgx.Tx) error {
		var mode string
		if err := tx.QueryRow(ctx, "SELECT mode FROM payment_sessions WHERE id = $1", sessionID).Scan(&mode); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `
			INSERT INTO payment_failures (id, session_id, attempt_id, mode, reason) VALUES ($1, $2, NULL, $3, $4)`,
			newID(prefixFailure), sessionID, mode, reason); err != nil {
			return err
		}
		var err error
		if after, err = findPayment(ctx, tx, sessionID); err != nil {
			return err
		}
		eventType := "payment.failed"
		if reason == "EXPIRED" {
			eventType = "payment.expired"
		}
		if err := s.event(ctx, tx, after, eventType); err != nil {
			return err
		}
		return audit(ctx, tx, "payment_session.ended", "payment_session", sessionID, map[string]any{"reason": reason})
	})
	return after, err
}

// DueForExpiry lists payments whose time ran out, with no result and no try running. A try still waiting
// for the customer must first be closed with the provider (it may have been approved at the last second).
func (s *Service) DueForExpiry(ctx context.Context, limit int) ([]string, error) {
	rows, err := s.db.Query(ctx, `
		SELECT v.session_id FROM payments_view v
		 WHERE v.status IN ('CREATED', 'AUTHORIZATION_REQUIRED') AND v.expires_at < now()
		   AND NOT EXISTS (SELECT 1 FROM payment_attempts a
		                    WHERE a.session_id = v.session_id
		                      AND NOT EXISTS (SELECT 1 FROM payment_failures f WHERE f.attempt_id = a.id))
		 ORDER BY v.expires_at LIMIT $1`, limit)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, pgx.RowTo[string])
}

// ---- helpers ----

// event saves a webhook event in the outbox, in the same transaction as the result it describes.
// The webhook worker (a later step) sends it to the client, signed.
func (s *Service) event(ctx context.Context, tx pgx.Tx, p Payment, eventType string) error {
	id := newID(prefixEvent)
	payload, err := json.Marshal(map[string]any{
		"id":         id,
		"type":       eventType,
		"apiVersion": "1.0",
		"mode":       p.Mode,
		"createdAt":  time.Now().UTC(),
		"data":       map[string]any{"object": s.View(p)},
	})
	if err != nil {
		return err
	}
	_, err = tx.Exec(ctx, `
		INSERT INTO outbox_events (id, merchant_id, mode, type, payload) VALUES ($1, $2, $3, $4, $5)`,
		id, p.MerchantID, p.Mode, eventType, payload)
	return err
}
