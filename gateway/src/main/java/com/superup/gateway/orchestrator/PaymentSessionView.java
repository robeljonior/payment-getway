package com.superup.gateway.orchestrator;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.superup.gateway.common.Money;
import com.superup.gateway.record.PaymentRow;
import java.time.Instant;
import java.util.Locale;

/**
 * The PaymentSession object of the Payment API (payment-api.openapi.yaml), built from one line of
 * payments_view. The same shape is used in API answers and in webhook events.
 */
public record PaymentSessionView(
        String id,
        String mode,
        String status,
        String orderReference,
        String amount,
        String currency,
        String amountRefunded,
        String fee,
        String net,
        PaymentMethod paymentMethod,
        Failure failure,
        String checkoutUrl,
        Instant expiresAt,
        Instant paidAt,
        Instant createdAt,
        @JsonRawValue String metadata) {

    public record PaymentMethod(String type, String maskedAccount) {
    }

    public record Failure(String code, String message) {
    }

    public static PaymentSessionView of(PaymentRow row, String checkoutBaseUrl) {
        return new PaymentSessionView(
                row.sessionId(),
                row.mode(),
                row.status(),
                row.orderReference(),
                Money.format(row.amount()),
                row.currency(),
                Money.format(row.amountRefunded()),
                row.fee() == null ? null : Money.format(row.fee()),
                row.net() == null ? null : Money.format(row.net()),
                row.paidMethod() == null ? null : new PaymentMethod(methodType(row.paidMethod()), row.paidPayerMasked()),
                failure(row),
                checkoutBaseUrl + "/" + row.sessionId(),
                row.expiresAt(),
                row.paidAt(),
                row.createdAt(),
                row.metadataJson());
    }

    /** The API groups methods as wallet or bank; the database keeps how the try was made. */
    private static String methodType(String method) {
        return switch (method) {
            case "push" -> "wallet";
            case "card" -> "card";
            default -> "bank";
        };
    }

    private static Failure failure(PaymentRow row) {
        return switch (row.status()) {
            case "EXPIRED" -> new Failure("expired", "The payment time ran out before the customer paid.");
            case "FAILED" -> new Failure("cancelled", "The customer cancelled the payment.");
            case "SUCCEEDED", "REFUNDED" -> null;
            default -> row.lastFailureReason() == null ? null
                    : new Failure(row.lastFailureReason().toLowerCase(Locale.ROOT),
                    "The last try failed. The customer can try another way until the payment expires.");
        };
    }
}
