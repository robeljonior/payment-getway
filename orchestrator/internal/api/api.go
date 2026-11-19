// Package api is the orchestrator's internal HTTP API. Only the gateway service and the connectors call
// it, never the public internet. Every call must carry the shared secret in X-Internal-Token.
package api

import (
	"crypto/subtle"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"strings"

	"github.com/robeljonior/payment-getway/orchestrator/internal/payments"
)

type Server struct {
	payments *payments.Service
	token    []byte
	log      *slog.Logger
}

func New(svc *payments.Service, internalToken string, log *slog.Logger) *Server {
	return &Server{payments: svc, token: []byte(internalToken), log: log}
}

// Handler returns every route. The contract is written out in the orchestrator README.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	})

	// Called by the gateway (layer 1), for a client that has passed every check.
	mux.HandleFunc("POST /internal/v1/payments", s.createPayment)
	mux.HandleFunc("GET /internal/v1/payments/{id}", s.readPayment)
	mux.HandleFunc("GET /internal/v1/payments/by-order/{orderReference}", s.readByOrder)

	// Called by checkout and by the connectors (layer 3), which others build.
	mux.HandleFunc("POST /internal/v1/payments/{id}/attempts", s.startAttempt)
	mux.HandleFunc("POST /internal/v1/payments/{id}/cancel", s.cancelPayment)
	mux.HandleFunc("GET /internal/v1/attempts/{id}", s.readAttempt)
	mux.HandleFunc("POST /internal/v1/attempts/{id}/step", s.moveAttempt)
	mux.HandleFunc("POST /internal/v1/attempts/{id}/success", s.recordSuccess)
	mux.HandleFunc("POST /internal/v1/attempts/{id}/failure", s.recordFailure)

	// Part 6: banks and wallets. The Back Office calls these (a later step).
	mux.HandleFunc("GET /internal/v1/providers", s.listProviders)
	mux.HandleFunc("PUT /internal/v1/providers/{code}", s.saveProvider)

	return s.guard(mux)
}

// guard checks the shared secret on every call except /health, and passes the caller and request ID on
// to the audit log.
func (s *Server) guard(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/health" {
			given := []byte(r.Header.Get("X-Internal-Token"))
			if subtle.ConstantTimeCompare(given, s.token) != 1 {
				writeError(w, http.StatusUnauthorized, "authentication_error", "invalid_internal_token",
					"X-Internal-Token is missing or wrong.", "")
				return
			}
		}
		if id := r.Header.Get("X-Request-Id"); id != "" {
			w.Header().Set("X-Request-Id", id)
		}
		defer func() {
			if v := recover(); v != nil {
				s.log.Error("panic", "path", r.URL.Path, "error", v)
				writeError(w, http.StatusInternalServerError, "api_error", "internal_error", "Something went wrong.", "")
			}
		}()
		next.ServeHTTP(w, r)
	})
}

// actor is who is calling. The gateway names the client's key; a connector names itself.
func actor(r *http.Request, actorType, actorID string) payments.Actor {
	if actorID == "" {
		actorID = r.Header.Get("X-Caller")
	}
	if actorID == "" {
		actorID = actorType
	}
	return payments.Actor{Type: actorType, ID: actorID, IP: r.Header.Get("X-Client-Ip"), RequestID: r.Header.Get("X-Request-Id")}
}

// readBody reads strict JSON: unknown fields and a second JSON value are refused.
func readBody(w http.ResponseWriter, r *http.Request, into any) bool {
	dec := json.NewDecoder(io.LimitReader(r.Body, 64<<10))
	dec.DisallowUnknownFields()
	if err := dec.Decode(into); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_request_error", "invalid_json", "The body is not valid JSON for this call: "+err.Error(), "")
		return false
	}
	if dec.More() {
		writeError(w, http.StatusBadRequest, "invalid_request_error", "invalid_json", "The body must hold one JSON object.", "")
		return false
	}
	return true
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

// writeError uses the error shape of docs/core/api-conventions.md. The gateway adds its own request ID
// before a client sees it.
func writeError(w http.ResponseWriter, status int, errType, code, message, param string) {
	body := map[string]any{"type": errType, "code": code, "message": message}
	if param != "" {
		body["param"] = param
	}
	writeJSON(w, status, map[string]any{"error": body})
}

func (s *Server) fail(w http.ResponseWriter, r *http.Request, err error) {
	var e *payments.Error
	switch {
	case errors.Is(err, payments.ErrNotFound):
		writeError(w, http.StatusNotFound, "invalid_request_error", "resource_missing", "Not found.", "")
	case errors.As(err, &e) && e.Kind == payments.KindInvalid:
		writeError(w, http.StatusBadRequest, "invalid_request_error", e.Code, e.Message, "")
	case errors.As(err, &e):
		writeError(w, http.StatusConflict, "invalid_request_error", e.Code, e.Message, "")
	default:
		s.log.Error("request failed", "path", r.URL.Path, "error", err)
		writeError(w, http.StatusInternalServerError, "api_error", "internal_error", "Something went wrong.", "")
	}
}

func clientOf(r *http.Request) (merchantID, mode string, ok bool) {
	merchantID, mode = r.URL.Query().Get("merchantId"), r.URL.Query().Get("mode")
	return merchantID, mode, merchantID != "" && (mode == "test" || mode == "live")
}

func trimmed(s *string) *string {
	if s == nil {
		return nil
	}
	v := strings.TrimSpace(*s)
	if v == "" {
		return nil
	}
	return &v
}
