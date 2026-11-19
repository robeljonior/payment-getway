package api_test

import (
	"bytes"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/robeljonior/payment-getway/orchestrator/internal/api"
	"github.com/robeljonior/payment-getway/orchestrator/internal/payments"
	"github.com/robeljonior/payment-getway/orchestrator/internal/testdb"
)

const token = "test-internal-token-0123456789abcdef"

type client struct {
	t   *testing.T
	url string
}

func (c client) call(method, path, tok string, body any) (int, map[string]any) {
	c.t.Helper()
	var reader io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		reader = bytes.NewReader(b)
	}
	req, _ := http.NewRequest(method, c.url+path, reader)
	req.Header.Set("X-Internal-Token", tok)
	req.Header.Set("X-Request-Id", "req_test")
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		c.t.Fatal(err)
	}
	defer res.Body.Close()
	var out map[string]any
	_ = json.NewDecoder(res.Body).Decode(&out)
	return res.StatusCode, out
}

func start(t *testing.T) (client, *payments.Service) {
	db := testdb.Open(t)
	svc := payments.NewService(db, 150, "https://pay.example.com/checkout")
	srv := httptest.NewServer(api.New(svc, token, slog.New(slog.NewTextHandler(io.Discard, nil))).Handler())
	t.Cleanup(srv.Close)
	return client{t, srv.URL}, svc
}

func errorCode(body map[string]any) string {
	e, _ := body["error"].(map[string]any)
	code, _ := e["code"].(string)
	return code
}

func TestAWholePaymentThroughTheInternalAPI(t *testing.T) {
	c, svc := start(t)
	merchant := testdb.MerchantID()
	provider := testdb.Provider(t, svc, "TESTING")
	create := map[string]any{
		"createKey": "ck_" + payments.RandomText(20), "merchantId": merchant, "mode": "test", "actorKeyId": "key_1",
		"orderReference": "ORDER_10001", "amount": 150000, "currency": "ETB",
		"returnUrl": "https://shop.example.com/payment/return", "expiresInSeconds": 900,
		"metadata": map[string]any{"cart": "42"},
	}

	status, p := c.call("POST", "/internal/v1/payments", token, create)
	if status != 201 || p["status"] != "CREATED" || p["amount"] != "1500.00" {
		t.Fatalf("create: %d %v", status, p)
	}
	id := p["id"].(string)
	if status, again := c.call("POST", "/internal/v1/payments", token, create); status != 200 || again["id"] != id {
		t.Fatalf("a retried create must return the same payment: %d %v", status, again)
	}

	status, a := c.call("POST", "/internal/v1/payments/"+id+"/attempts", token,
		map[string]any{"method": "push", "providerCode": provider, "payerMasked": "+2519****1234"})
	if status != 201 || a["step"] != "ACTION_REQUIRED" {
		t.Fatalf("start attempt: %d %v", status, a)
	}
	attempt := a["id"].(string)
	if status, a = c.call("POST", "/internal/v1/attempts/"+attempt+"/step", token,
		map[string]any{"step": "PENDING", "providerRef": "TB-1"}); status != 200 || a["providerRef"] != "TB-1" {
		t.Fatalf("move: %d %v", status, a)
	}
	status, paid := c.call("POST", "/internal/v1/attempts/"+attempt+"/success", token, map[string]any{"confirmedBy": "status_check"})
	if status != 200 || paid["status"] != "SUCCEEDED" || paid["fee"] != "22.50" || paid["net"] != "1477.50" {
		t.Fatalf("success: %d %v", status, paid)
	}
	if status, again := c.call("POST", "/internal/v1/attempts/"+attempt+"/success", token, map[string]any{"confirmedBy": "status_check"}); status != 409 {
		t.Fatalf("a second success must be refused: %d %v", status, again)
	}

	q := "?merchantId=" + merchant + "&mode=test"
	if status, got := c.call("GET", "/internal/v1/payments/"+id+q, token, nil); status != 200 || got["status"] != "SUCCEEDED" {
		t.Fatalf("read: %d %v", status, got)
	}
	if status, got := c.call("GET", "/internal/v1/payments/by-order/ORDER_10001"+q, token, nil); status != 200 || got["id"] != id {
		t.Fatalf("by order: %d %v", status, got)
	}
	// Another client, or the other mode, can't see it.
	if status, _ := c.call("GET", "/internal/v1/payments/"+id+"?merchantId=m_other&mode=test", token, nil); status != 404 {
		t.Fatalf("another client read it: %d", status)
	}
	if status, _ := c.call("GET", "/internal/v1/payments/"+id+"?merchantId="+merchant+"&mode=live", token, nil); status != 404 {
		t.Fatalf("live mode read a test payment: %d", status)
	}
	if status, a = c.call("GET", "/internal/v1/attempts/"+attempt, token, nil); status != 200 || a["result"] != "SUCCEEDED" {
		t.Fatalf("read attempt: %d %v", status, a)
	}
}

func TestAFailedTryThenCancel(t *testing.T) {
	c, svc := start(t)
	provider := testdb.Provider(t, svc, "TESTING")
	_, p := c.call("POST", "/internal/v1/payments", token, map[string]any{
		"createKey": "ck_" + payments.RandomText(20), "merchantId": testdb.MerchantID(), "mode": "test",
		"orderReference": "ORDER_2", "amount": 5000, "currency": "ETB", "returnUrl": "https://shop.example.com/r",
		"expiresInSeconds": 900})
	id := p["id"].(string)
	_, a := c.call("POST", "/internal/v1/payments/"+id+"/attempts", token, map[string]any{"method": "otp", "providerCode": provider})
	attempt := a["id"].(string)

	if status, _ := c.call("POST", "/internal/v1/payments/"+id+"/cancel", token, nil); status != 409 {
		t.Fatalf("cancel while a try runs must be refused: %d", status)
	}
	status, failed := c.call("POST", "/internal/v1/attempts/"+attempt+"/failure", token, map[string]any{"reason": "OTP_FAILED"})
	if status != 200 || failed["status"] != "AUTHORIZATION_REQUIRED" {
		t.Fatalf("failure: %d %v", status, failed)
	}
	if f, _ := failed["failure"].(map[string]any); f["code"] != "otp_failed" {
		t.Fatalf("failure code: %v", failed["failure"])
	}
	status, cancelled := c.call("POST", "/internal/v1/payments/"+id+"/cancel", token, nil)
	if status != 200 || cancelled["status"] != "FAILED" {
		t.Fatalf("cancel: %d %v", status, cancelled)
	}
}

func TestTheAPIRefusesBadCalls(t *testing.T) {
	c, _ := start(t)
	if status, body := c.call("GET", "/internal/v1/providers", "wrong", nil); status != 401 || errorCode(body) != "invalid_internal_token" {
		t.Fatalf("wrong token: %d %v", status, body)
	}
	if status, _ := c.call("GET", "/health", "", nil); status != 200 {
		t.Fatalf("health: %d", status)
	}
	cases := []struct {
		name string
		body map[string]any
		code string
	}{
		{"unknown field", map[string]any{"amount": 1, "colour": "red"}, "invalid_json"},
		{"amount as text", map[string]any{"amount": "1500.00"}, "invalid_json"},
		{"zero amount", map[string]any{"createKey": "ck_12345678", "merchantId": "m_1", "mode": "test",
			"orderReference": "O", "amount": 0, "currency": "ETB", "returnUrl": "https://x", "expiresInSeconds": 900}, "invalid_amount"},
		{"bad mode", map[string]any{"createKey": "ck_12345678", "merchantId": "m_1", "mode": "real",
			"orderReference": "O", "amount": 1, "currency": "ETB", "returnUrl": "https://x", "expiresInSeconds": 900}, "invalid_mode"},
	}
	for _, tc := range cases {
		if status, body := c.call("POST", "/internal/v1/payments", token, tc.body); status != 400 || errorCode(body) != tc.code {
			t.Errorf("%s: %d %v", tc.name, status, body)
		}
	}
	if status, _ := c.call("GET", "/internal/v1/payments/ps_nothing", token, nil); status != 404 {
		t.Fatalf("unknown payment: %d", status)
	}
	if status, body := c.call("POST", "/internal/v1/attempts/att_nothing/failure", token, map[string]any{"reason": "EXPIRED"}); status != 400 {
		t.Fatalf("a try can't fail with EXPIRED: %d %v", status, body)
	}
	if status, body := c.call("PUT", "/internal/v1/providers/telebirr", token, map[string]any{"nameEn": "x", "type": "wallet", "connector": "x"}); status != 400 || errorCode(body) != "invalid_code" {
		t.Fatalf("lower-case code: %d %v", status, body)
	}
}
