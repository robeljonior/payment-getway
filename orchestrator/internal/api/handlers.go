package api

import (
	"net/http"

	"github.com/robeljonior/payment-getway/orchestrator/internal/payments"
)

// ---- for the gateway ----

type createRequest struct {
	payments.NewPayment
	ActorKeyID string `json:"actorKeyId"` // the client's API key ID, for the audit log
}

func (s *Server) createPayment(w http.ResponseWriter, r *http.Request) {
	var body createRequest
	if !readBody(w, r, &body) {
		return
	}
	ctx := payments.WithActor(r.Context(), actor(r, "merchant_key", body.ActorKeyID))
	p, created, err := s.payments.Create(ctx, body.NewPayment)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	status := http.StatusOK // made before with the same create key
	if created {
		status = http.StatusCreated
	}
	writeJSON(w, status, s.payments.View(p))
}

func (s *Server) readPayment(w http.ResponseWriter, r *http.Request) {
	merchantID, mode, ok := clientOf(r)
	var p payments.Payment
	var err error
	if ok {
		p, err = s.payments.Find(r.Context(), merchantID, mode, r.PathValue("id"))
	} else if r.URL.Query().Get("merchantId") == "" && r.URL.Query().Get("mode") == "" {
		p, err = s.payments.FindByID(r.Context(), r.PathValue("id")) // checkout and connectors
	} else {
		writeError(w, http.StatusBadRequest, "invalid_request_error", "invalid_client", "Give both merchantId and mode (test or live), or neither.", "")
		return
	}
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, s.payments.View(p))
}

func (s *Server) readByOrder(w http.ResponseWriter, r *http.Request) {
	merchantID, mode, ok := clientOf(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "invalid_request_error", "invalid_client", "merchantId and mode (test or live) are required.", "")
		return
	}
	p, err := s.payments.LatestForOrder(r.Context(), merchantID, mode, r.PathValue("orderReference"))
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, s.payments.View(p))
}

// ---- for checkout and the connectors ----

func (s *Server) startAttempt(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Method       string  `json:"method"`
		ProviderCode string  `json:"providerCode"`
		PayerMasked  *string `json:"payerMasked"`
	}
	if !readBody(w, r, &body) {
		return
	}
	ctx := payments.WithActor(r.Context(), actor(r, "system", "checkout"))
	a, err := s.payments.StartAttempt(ctx, r.PathValue("id"), body.Method, body.ProviderCode, trimmed(body.PayerMasked))
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusCreated, a)
}

func (s *Server) cancelPayment(w http.ResponseWriter, r *http.Request) {
	ctx := payments.WithActor(r.Context(), actor(r, "system", "checkout"))
	p, err := s.payments.EndPayment(ctx, r.PathValue("id"), "CANCELLED")
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, s.payments.View(p))
}

func (s *Server) readAttempt(w http.ResponseWriter, r *http.Request) {
	a, err := s.payments.FindAttempt(r.Context(), r.PathValue("id"))
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, a)
}

func (s *Server) moveAttempt(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Step        string  `json:"step"`
		ProviderRef *string `json:"providerRef"`
	}
	if !readBody(w, r, &body) {
		return
	}
	ctx := payments.WithActor(r.Context(), actor(r, "connector", ""))
	a, err := s.payments.MoveAttempt(ctx, r.PathValue("id"), body.Step, trimmed(body.ProviderRef))
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, a)
}

func (s *Server) recordSuccess(w http.ResponseWriter, r *http.Request) {
	var body struct {
		ProviderRef *string `json:"providerRef"`
		ConfirmedBy string  `json:"confirmedBy"`
		// Only for a late success found by reconciliation after the try was recorded as failed.
		CorrectsFailureID *string `json:"correctsFailureId"`
		ApprovalID        *string `json:"approvalId"`
	}
	if !readBody(w, r, &body) {
		return
	}
	var fix *payments.Correction
	if body.CorrectsFailureID != nil || body.ApprovalID != nil {
		if trimmed(body.CorrectsFailureID) == nil || trimmed(body.ApprovalID) == nil {
			writeError(w, http.StatusBadRequest, "invalid_request_error", "invalid_correction",
				"A correction needs both correctsFailureId and approvalId.", "")
			return
		}
		fix = &payments.Correction{FailureID: *body.CorrectsFailureID, ApprovalID: *body.ApprovalID}
	}
	ctx := payments.WithActor(r.Context(), actor(r, "connector", ""))
	p, err := s.payments.RecordSuccess(ctx, r.PathValue("id"), trimmed(body.ProviderRef), body.ConfirmedBy, fix)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, s.payments.View(p))
}

func (s *Server) recordFailure(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Reason       string  `json:"reason"`
		ProviderCode *string `json:"providerCode"` // the bank's own error code, if any
	}
	if !readBody(w, r, &body) {
		return
	}
	ctx := payments.WithActor(r.Context(), actor(r, "connector", ""))
	p, err := s.payments.RecordTryFailure(ctx, r.PathValue("id"), body.Reason, trimmed(body.ProviderCode))
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, s.payments.View(p))
}

// ---- part 6: providers ----

func (s *Server) listProviders(w http.ResponseWriter, r *http.Request) {
	list, err := s.payments.Providers(r.Context())
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"data": list})
}

func (s *Server) saveProvider(w http.ResponseWriter, r *http.Request) {
	var body payments.Provider
	if !readBody(w, r, &body) {
		return
	}
	if body.Code == "" {
		body.Code = r.PathValue("code")
	}
	if body.Code != r.PathValue("code") {
		writeError(w, http.StatusBadRequest, "invalid_request_error", "invalid_code", "The code in the body must match the path.", "")
		return
	}
	ctx := payments.WithActor(r.Context(), actor(r, "staff", ""))
	p, err := s.payments.SaveProvider(ctx, body)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, p)
}
