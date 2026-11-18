package payments

import (
	"errors"
	"fmt"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
)

// ErrNotFound means the payment, try or provider does not exist (or belongs to another client or mode).
var ErrNotFound = errors.New("not found")

// Error is a refusal with a code the API can show. Kind decides the HTTP status.
type Error struct {
	Kind    Kind
	Code    string
	Message string
}

type Kind int

const (
	KindInvalid  Kind = iota // 400: the request itself is wrong
	KindConflict             // 409: the request is fine, but the payment's state or a rule refuses it
)

func (e *Error) Error() string { return e.Code + ": " + e.Message }

func invalid(code, format string, a ...any) error {
	return &Error{Kind: KindInvalid, Code: code, Message: fmt.Sprintf(format, a...)}
}

func conflict(code, format string, a ...any) error {
	return &Error{Kind: KindConflict, Code: code, Message: fmt.Sprintf(format, a...)}
}

// fromDB turns the database's refusals into errors the API can show.
// The rules in the migrations raise check_violation (23514). A unique key gives 23505.
func fromDB(err error) error {
	if err == nil {
		return nil
	}
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrNotFound
	}
	var pg *pgconn.PgError
	if errors.As(err, &pg) {
		switch pg.Code {
		case "23514":
			return conflict("rule_violation", "%s", pg.Message)
		case "23505":
			return conflict("already_exists", "%s", pg.Message)
		}
	}
	return err
}
