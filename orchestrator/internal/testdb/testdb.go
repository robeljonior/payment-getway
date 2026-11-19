// Package testdb gives tests a migrated database. Payment rows can never be deleted, so tests do not
// clean up: each test uses its own new client ID and provider instead.
package testdb

import (
	"context"
	"os"
	"strings"
	"testing"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/robeljonior/payment-getway/orchestrator/internal/payments"
	"github.com/robeljonior/payment-getway/orchestrator/internal/store"
)

// URL is TEST_DATABASE_URL, or a local orchestrator_test database.
func URL() string {
	if v := os.Getenv("TEST_DATABASE_URL"); v != "" {
		return v
	}
	return "postgres://postgres:" + os.Getenv("DB_PASSWORD") + "@localhost:5432/orchestrator_test"
}

func Open(t *testing.T) *pgxpool.Pool {
	t.Helper()
	db, err := store.Open(context.Background(), URL())
	if err != nil {
		t.Fatalf("test database %s: %v (start PostgreSQL and create the database first; see the README)", URL(), err)
	}
	t.Cleanup(db.Close)
	return db
}

// Provider adds a new provider with its own code, so ledger balances in a test start from zero.
func Provider(t *testing.T, svc *payments.Service, status string) string {
	t.Helper()
	code := "TEST_" + strings.ToUpper(payments.RandomText(10))
	if _, err := svc.SaveProvider(context.Background(), payments.Provider{
		Code: code, NameEn: "Test wallet", Type: "wallet", Connector: "test", Status: status,
	}); err != nil {
		t.Fatal(err)
	}
	return code
}

func MerchantID() string { return "m_" + payments.RandomText(20) }
