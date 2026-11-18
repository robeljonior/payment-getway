package payments

import (
	"context"
	"encoding/json"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
)

// querier is what both the pool and a transaction can do.
type querier interface {
	Exec(ctx context.Context, sql string, args ...any) (pgconn.CommandTag, error)
	Query(ctx context.Context, sql string, args ...any) (pgx.Rows, error)
	QueryRow(ctx context.Context, sql string, args ...any) pgx.Row
}

// Payment is one line of payments_view (part 4). The status is never stored: the view works it out
// from parts 1 to 3.
type Payment struct {
	SessionID         string
	MerchantID        string
	Mode              string
	OrderReference    string
	Amount            int64
	Currency          string
	Description       *string
	ReturnURL         string
	Metadata          json.RawMessage
	ExpiresAt         time.Time
	CreatedAt         time.Time
	Fee               *int64
	Net               *int64
	PaidAt            *time.Time
	PaidMethod        *string
	PaidPayerMasked   *string
	AmountRefunded    int64
	Tries             int64
	LastFailureReason *string
	Status            string
}

const viewColumns = `session_id, merchant_id, mode, order_reference, amount, currency, description, return_url,
	metadata::text, expires_at, created_at, fee, net, paid_at, paid_method, paid_payer_masked,
	amount_refunded, tries, last_failure_reason, status`

func scanPayment(row pgx.Row) (Payment, error) {
	var p Payment
	var metadata string
	err := row.Scan(&p.SessionID, &p.MerchantID, &p.Mode, &p.OrderReference, &p.Amount, &p.Currency,
		&p.Description, &p.ReturnURL, &metadata, &p.ExpiresAt, &p.CreatedAt, &p.Fee, &p.Net, &p.PaidAt,
		&p.PaidMethod, &p.PaidPayerMasked, &p.AmountRefunded, &p.Tries, &p.LastFailureReason, &p.Status)
	p.Metadata = json.RawMessage(metadata)
	return p, err
}

func findPayment(ctx context.Context, q querier, sessionID string) (Payment, error) {
	p, err := scanPayment(q.QueryRow(ctx, "SELECT "+viewColumns+" FROM payments_view WHERE session_id = $1", sessionID))
	return p, fromDB(err)
}

// Attempt is one try to pay (part 1, with the session).
type Attempt struct {
	ID           string    `json:"id"`
	SessionID    string    `json:"paymentId"`
	Mode         string    `json:"mode"`
	Method       string    `json:"method"`
	ProviderCode string    `json:"providerCode"`
	PayerMasked  *string   `json:"payerMasked"`
	ProviderRef  *string   `json:"providerRef"`
	Step         string    `json:"step"`
	Result       *string   `json:"result"` // null while it runs, then SUCCEEDED or the failure reason
	CreatedAt    time.Time `json:"createdAt"`
	UpdatedAt    time.Time `json:"updatedAt"`
}

func findAttempt(ctx context.Context, q querier, attemptID string) (Attempt, error) {
	var a Attempt
	err := q.QueryRow(ctx, `
		SELECT a.id, a.session_id, a.mode, a.method, p.code, a.payer_masked, a.provider_ref, a.step,
		       CASE WHEN EXISTS (SELECT 1 FROM payment_successes s WHERE s.attempt_id = a.id) THEN 'SUCCEEDED'
		            ELSE (SELECT f.reason FROM payment_failures f WHERE f.attempt_id = a.id) END,
		       a.created_at, a.updated_at
		  FROM payment_attempts a JOIN providers p ON p.id = a.provider_id
		 WHERE a.id = $1`, attemptID).
		Scan(&a.ID, &a.SessionID, &a.Mode, &a.Method, &a.ProviderCode, &a.PayerMasked, &a.ProviderRef, &a.Step,
			&a.Result, &a.CreatedAt, &a.UpdatedAt)
	return a, fromDB(err)
}

// ---- events and audit ----

type actorKey struct{}

// Actor is who asked for a change. It goes into the audit log.
type Actor struct {
	Type      string // merchant_key, staff, system, provider or connector
	ID        string
	IP        string
	RequestID string
}

// WithActor puts the actor into ctx for the audit log.
func WithActor(ctx context.Context, a Actor) context.Context {
	return context.WithValue(ctx, actorKey{}, a)
}

func actorOf(ctx context.Context) Actor {
	if a, ok := ctx.Value(actorKey{}).(Actor); ok {
		return a
	}
	return Actor{Type: "system", ID: "orchestrator"}
}

func audit(ctx context.Context, q querier, action, objectType, objectID string, after any) error {
	a := actorOf(ctx)
	data, err := json.Marshal(after)
	if err != nil {
		return err
	}
	_, err = q.Exec(ctx, `
		INSERT INTO audit_logs (actor_type, actor_id, action, object_type, object_id, after_data, ip, correlation_id)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8)`,
		a.Type, a.ID, action, objectType, objectID, data, nullIfEmpty(a.IP), nullIfEmpty(a.RequestID))
	return err
}

func nullIfEmpty(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}
