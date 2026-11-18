package com.superup.gateway.record;

import java.time.Instant;

/** One line of payments_view (part 4): a payment and its current status. */
public record PaymentRow(
        String sessionId,
        String merchantId,
        String mode,
        String orderReference,
        long amount,
        String currency,
        String description,
        String returnUrl,
        String metadataJson,
        Instant expiresAt,
        Instant createdAt,
        Long fee,
        Long net,
        Instant paidAt,
        String paidMethod,
        String paidPayerMasked,
        long amountRefunded,
        int tries,
        String lastFailureReason,
        String status) {
}
