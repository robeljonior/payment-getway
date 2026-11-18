package com.superup.gateway.api;

import java.util.Map;

/**
 * The body of POST /api/v1/payment-sessions. Unknown fields are rejected.
 * Marketplace fields (appId, customerRef), customerHint, paymentMethods and customization
 * come in later steps.
 */
public record CreatePaymentSessionRequest(
        String orderReference,
        String amount,
        String currency,
        String description,
        String returnUrl,
        String webhookUrl,
        Integer expiresInSeconds,
        Map<String, String> metadata) {
}
