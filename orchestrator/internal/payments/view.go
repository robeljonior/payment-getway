package payments

import (
	"encoding/json"
	"strings"
	"time"
)

// Session is the PaymentSession object of the Payment API (payment-api.openapi.yaml), built from one
// line of payments_view. The gateway passes it to clients as it is, and webhook events carry it too.
type Session struct {
	ID             string          `json:"id"`
	Mode           string          `json:"mode"`
	Status         string          `json:"status"`
	OrderReference string          `json:"orderReference"`
	Amount         string          `json:"amount"`
	Currency       string          `json:"currency"`
	AmountRefunded string          `json:"amountRefunded"`
	Fee            *string         `json:"fee"`
	Net            *string         `json:"net"`
	PaymentMethod  *PaymentMethod  `json:"paymentMethod"`
	Failure        *Failure        `json:"failure"`
	CheckoutURL    string          `json:"checkoutUrl"`
	ExpiresAt      time.Time       `json:"expiresAt"`
	PaidAt         *time.Time      `json:"paidAt"`
	CreatedAt      time.Time       `json:"createdAt"`
	Metadata       json.RawMessage `json:"metadata"`
}

type PaymentMethod struct {
	Type          string  `json:"type"`
	MaskedAccount *string `json:"maskedAccount"`
}

type Failure struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

// SessionOf builds the API object. checkoutBaseURL is where customers pay.
func SessionOf(p Payment, checkoutBaseURL string) Session {
	s := Session{
		ID:             p.SessionID,
		Mode:           p.Mode,
		Status:         p.Status,
		OrderReference: p.OrderReference,
		Amount:         FormatSantim(p.Amount),
		Currency:       p.Currency,
		AmountRefunded: FormatSantim(p.AmountRefunded),
		CheckoutURL:    strings.TrimRight(checkoutBaseURL, "/") + "/" + p.SessionID,
		ExpiresAt:      p.ExpiresAt.UTC(),
		CreatedAt:      p.CreatedAt.UTC(),
		Metadata:       p.Metadata,
		Failure:        failureOf(p),
	}
	if p.Fee != nil {
		v := FormatSantim(*p.Fee)
		s.Fee = &v
	}
	if p.Net != nil {
		v := FormatSantim(*p.Net)
		s.Net = &v
	}
	if p.PaidAt != nil {
		t := p.PaidAt.UTC()
		s.PaidAt = &t
	}
	if p.PaidMethod != nil {
		s.PaymentMethod = &PaymentMethod{Type: methodType(*p.PaidMethod), MaskedAccount: p.PaidPayerMasked}
	}
	return s
}

// The API groups methods as wallet, bank or card. The database keeps how the try was made.
func methodType(method string) string {
	switch method {
	case "push":
		return "wallet"
	case "card":
		return "card"
	default:
		return "bank"
	}
}

func failureOf(p Payment) *Failure {
	switch p.Status {
	case "EXPIRED":
		return &Failure{"expired", "The payment time ran out before the customer paid."}
	case "FAILED":
		return &Failure{"cancelled", "The customer cancelled the payment."}
	case "SUCCEEDED", "REFUNDED":
		return nil
	}
	if p.LastFailureReason == nil {
		return nil
	}
	return &Failure{strings.ToLower(*p.LastFailureReason),
		"The last try failed. The customer can try another way until the payment expires."}
}
