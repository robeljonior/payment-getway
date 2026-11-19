package payments

import (
	"context"
	"time"
)

// The double-entry ledger (docs/05-money-and-ledger.md). A journal is a set of lines whose debits equal
// its credits. The database checks this at commit, and journals can never be changed: a correction is a
// new journal.

type Line struct {
	Account     string // for example merchant:m_123:pending
	AccountType string // asset, liability, revenue or expense
	Debit       bool
	Amount      int64
}

func Debit(account, accountType string, amount int64) Line {
	return Line{account, accountType, true, amount}
}

func Credit(account, accountType string, amount int64) Line {
	return Line{account, accountType, false, amount}
}

var addisAbaba = func() *time.Location {
	if loc, err := time.LoadLocation("Africa/Addis_Ababa"); err == nil {
		return loc
	}
	return time.FixedZone("EAT", 3*60*60) // Ethiopia has no daylight saving time
}()

// PostJournal writes one journal inside the transaction q and returns its ID. Lines with a zero amount are
// skipped. sourceEventID is unique, so the same business event can never be posted twice.
func PostJournal(ctx context.Context, q querier, journalType, sourceEventID, mode string, lines []Line) (string, error) {
	id := newID(prefixJournal)
	if _, err := q.Exec(ctx, `
		INSERT INTO ledger_journals (id, source_event_id, type, mode, value_date) VALUES ($1, $2, $3, $4, $5)`,
		id, sourceEventID, journalType, mode, time.Now().In(addisAbaba).Format("2006-01-02")); err != nil {
		return "", fromDB(err)
	}
	for _, line := range lines {
		if line.Amount == 0 {
			continue
		}
		var accountID int64
		err := q.QueryRow(ctx, `
			INSERT INTO ledger_accounts (code, mode, type, currency) VALUES ($1, $2, $3, 'ETB')
			ON CONFLICT (code, mode) DO UPDATE SET code = EXCLUDED.code
			RETURNING id`, line.Account, mode, line.AccountType).Scan(&accountID)
		if err != nil {
			return "", fromDB(err)
		}
		direction := "credit"
		if line.Debit {
			direction = "debit"
		}
		if _, err := q.Exec(ctx, `
			INSERT INTO ledger_entries (journal_id, account_id, direction, amount, currency)
			VALUES ($1, $2, $3, $4, 'ETB')`, id, accountID, direction, line.Amount); err != nil {
			return "", fromDB(err)
		}
	}
	return id, nil
}

// Balance is debits minus credits for one account (positive for assets, negative for what we owe).
func (s *Service) Balance(ctx context.Context, account, mode string) (int64, error) {
	var v int64
	err := s.db.QueryRow(ctx, `
		SELECT coalesce(sum(CASE WHEN e.direction = 'debit' THEN e.amount ELSE -e.amount END), 0)
		  FROM ledger_entries e JOIN ledger_accounts a ON a.id = e.account_id
		 WHERE a.code = $1 AND a.mode = $2`, account, mode).Scan(&v)
	return v, err
}
