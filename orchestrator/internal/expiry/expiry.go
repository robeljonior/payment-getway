// Package expiry closes payments whose time ran out. A payment with a try still running is skipped,
// because money may be moving (the database also refuses it).
package expiry

import (
	"context"
	"log/slog"
	"time"

	"github.com/robeljonior/payment-getway/orchestrator/internal/payments"
)

// Run checks every `every` until ctx ends.
func Run(ctx context.Context, svc *payments.Service, every time.Duration, log *slog.Logger) {
	t := time.NewTicker(every)
	defer t.Stop()
	for {
		Once(ctx, svc, log)
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

// Once ends every payment that is due now and returns how many it ended.
func Once(ctx context.Context, svc *payments.Service, log *slog.Logger) int {
	ids, err := svc.DueForExpiry(ctx, 100)
	if err != nil {
		if ctx.Err() == nil {
			log.Error("expiry: list due payments", "error", err)
		}
		return 0
	}
	ended := 0
	ctx = payments.WithActor(ctx, payments.Actor{Type: "system", ID: "expiry-job"})
	for _, id := range ids {
		if _, err := svc.EndPayment(ctx, id, "EXPIRED"); err != nil {
			// A try started at the same moment. It will be checked again next time.
			log.Info("expiry: skipped", "payment", id, "reason", err)
			continue
		}
		ended++
	}
	return ended
}
