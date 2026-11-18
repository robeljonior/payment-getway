package com.superup.gateway.gateway;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Who is calling, worked out from the API key. The client never sends its own ID:
 * the key decides both the client and the mode (test or live).
 */
public record Caller(String merchantId, String mode, String keyId, String merchantStatus, String ip) {

    static final String ATTRIBUTE = "gateway.caller";

    public static Caller of(HttpServletRequest request) {
        Caller caller = (Caller) request.getAttribute(ATTRIBUTE);
        if (caller == null) {
            throw ApiException.unauthenticated();
        }
        return caller;
    }

    /** A suspended client can still read its payments, but can't start new ones. */
    public void mustBeAllowedToCreatePayments() {
        if ("SUSPENDED".equals(merchantStatus)) {
            throw ApiException.forbidden("merchant_suspended",
                    "This account is suspended and can't create new payments.");
        }
    }
}
