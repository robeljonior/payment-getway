package com.superup.gateway.orchestrator;

/** A payment request that has passed every layer 1 check. Amounts are in santim. */
public record NewPayment(
        String orderReference,
        long amount,
        String currency,
        String description,
        String returnUrl,
        String webhookUrl,
        int expiresInSeconds,
        String metadataJson) {
}
