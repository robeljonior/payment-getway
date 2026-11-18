package payments

import (
	"context"
	"regexp"

	"github.com/jackc/pgx/v5"
)

// Provider is part 6: one bank, wallet, switch or card processor.
type Provider struct {
	Code      string  `json:"code"`
	NameEn    string  `json:"nameEn"`
	NameAm    *string `json:"nameAm"`
	Type      string  `json:"type"`
	Connector string  `json:"connector"`
	Status    string  `json:"status"`
	MinAmount *int64  `json:"minAmount"`
	MaxAmount *int64  `json:"maxAmount"`
}

var providerCode = regexp.MustCompile(`^[A-Z][A-Z0-9_]{1,39}$`)

// SaveProvider adds a provider or changes one, by its code. The Back Office calls this (a later step).
func (s *Service) SaveProvider(ctx context.Context, p Provider) (Provider, error) {
	switch {
	case !providerCode.MatchString(p.Code):
		return p, invalid("invalid_code", "code must be capital letters, digits and _, like TELEBIRR.")
	case p.NameEn == "":
		return p, invalid("invalid_name", "nameEn is required.")
	case p.Type != "bank" && p.Type != "wallet" && p.Type != "switch" && p.Type != "card":
		return p, invalid("invalid_type", "type must be bank, wallet, switch or card.")
	case p.Status != "" && p.Status != "DRAFT" && p.Status != "TESTING" && p.Status != "ACTIVE" && p.Status != "PAUSED" && p.Status != "RETIRED":
		return p, invalid("invalid_status", "status must be DRAFT, TESTING, ACTIVE, PAUSED or RETIRED.")
	case p.Connector == "":
		return p, invalid("invalid_connector", "connector is required.")
	}
	if p.Status == "" {
		p.Status = "DRAFT"
	}
	err := s.inTx(ctx, func(tx pgx.Tx) error {
		_, err := tx.Exec(ctx, `
			INSERT INTO providers (id, code, name_en, name_am, type, connector, status, min_amount, max_amount)
			VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
			ON CONFLICT (code) DO UPDATE SET name_en = EXCLUDED.name_en, name_am = EXCLUDED.name_am,
			    type = EXCLUDED.type, connector = EXCLUDED.connector, status = EXCLUDED.status,
			    min_amount = EXCLUDED.min_amount, max_amount = EXCLUDED.max_amount`,
			"prov_"+RandomText(16), p.Code, p.NameEn, p.NameAm, p.Type, p.Connector, p.Status, p.MinAmount, p.MaxAmount)
		if err != nil {
			return err
		}
		return audit(ctx, tx, "provider.saved", "provider", p.Code, p)
	})
	return p, err
}

// Providers lists every provider, for checkout (which shows the ACTIVE ones) and the Back Office.
func (s *Service) Providers(ctx context.Context) ([]Provider, error) {
	rows, err := s.db.Query(ctx, `SELECT code, name_en, name_am, type, connector, status, min_amount, max_amount
		FROM providers ORDER BY code`)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(r pgx.CollectableRow) (Provider, error) {
		var p Provider
		err := r.Scan(&p.Code, &p.NameEn, &p.NameAm, &p.Type, &p.Connector, &p.Status, &p.MinAmount, &p.MaxAmount)
		return p, err
	})
}
