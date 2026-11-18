// The orchestrator service (layer 2). It owns the payment record (layer 4): the six parts, the ledger,
// outbox events and the audit log. See the README for how to run it.
package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/robeljonior/payment-getway/orchestrator/internal/api"
	"github.com/robeljonior/payment-getway/orchestrator/internal/config"
	"github.com/robeljonior/payment-getway/orchestrator/internal/expiry"
	"github.com/robeljonior/payment-getway/orchestrator/internal/payments"
	"github.com/robeljonior/payment-getway/orchestrator/internal/store"
)

func main() {
	log := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	if err := run(log); err != nil {
		log.Error("orchestrator stopped", "error", err)
		os.Exit(1)
	}
}

func run(log *slog.Logger) error {
	cfg, err := config.FromEnv()
	if err != nil {
		return err
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	db, err := store.Open(ctx, cfg.DatabaseURL)
	if err != nil {
		return err
	}
	defer db.Close()

	svc := payments.NewService(db, cfg.FeeBasisPoints, cfg.CheckoutBaseURL)
	if cfg.ExpiryEvery > 0 {
		go expiry.Run(ctx, svc, cfg.ExpiryEvery, log)
	}

	server := &http.Server{
		Addr:              ":" + cfg.Port,
		Handler:           api.New(svc, cfg.InternalToken, log).Handler(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      30 * time.Second,
	}
	go func() {
		<-ctx.Done()
		shutdown, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		_ = server.Shutdown(shutdown)
	}()
	log.Info("orchestrator listening", "port", cfg.Port)
	if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	return nil
}
