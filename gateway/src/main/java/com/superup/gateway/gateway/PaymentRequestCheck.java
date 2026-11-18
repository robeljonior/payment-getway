package com.superup.gateway.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superup.gateway.api.CreatePaymentSessionRequest;
import com.superup.gateway.common.Money;
import com.superup.gateway.orchestrator.NewPayment;
import com.superup.gateway.record.MerchantRecords;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Layer 1, check "is the request right?" for a new payment: format, amount, limits and URLs.
 * Every error names the field (param) and has a stable code.
 */
@Component
public class PaymentRequestCheck {

    private final MerchantRecords merchants;
    private final ObjectMapper json;

    public PaymentRequestCheck(MerchantRecords merchants, ObjectMapper json) {
        this.merchants = merchants;
        this.json = json;
    }

    public NewPayment check(Caller caller, CreatePaymentSessionRequest r) {
        if (r == null) {
            throw ApiException.invalid("body_missing", "Send the payment as a JSON body.", null);
        }
        String orderReference = required(r.orderReference(), "orderReference");
        if (orderReference.length() > 100) {
            throw ApiException.invalid("order_reference_too_long", "orderReference can be at most 100 characters.",
                    "orderReference");
        }
        Long amount = Money.parseSantim(r.amount());
        if (amount == null || amount <= 0) {
            throw ApiException.invalid("amount_invalid",
                    "amount must be a string with two decimals above zero, for example \"1500.00\".", "amount");
        }
        if (!"ETB".equals(r.currency())) {
            throw ApiException.invalid("currency_invalid", "currency must be \"ETB\".", "currency");
        }
        if (r.description() != null && r.description().length() > 200) {
            throw ApiException.invalid("description_too_long", "description can be at most 200 characters.",
                    "description");
        }
        int expiresIn = r.expiresInSeconds() == null ? 900 : r.expiresInSeconds();
        if (expiresIn < 300 || expiresIn > 86_400) {
            throw ApiException.invalid("expires_in_invalid",
                    "expiresInSeconds must be between 300 (5 minutes) and 86400 (24 hours).", "expiresInSeconds");
        }
        Map<String, String> metadata = r.metadata() == null ? Map.of() : r.metadata();
        if (metadata.size() > 20 || metadata.values().stream().anyMatch(v -> v == null || v.length() > 500)) {
            throw ApiException.invalid("metadata_invalid",
                    "metadata can have at most 20 entries, each a text of at most 500 characters.", "metadata");
        }

        MerchantRecords.Settings settings = merchants.settings(caller.merchantId(), caller.mode())
                .orElseThrow(() -> ApiException.forbidden("merchant_not_configured",
                        "This account has no settings for " + caller.mode() + " mode yet. Ask support to finish setup."));
        if (amount > settings.limitPerPayment()) {
            throw ApiException.invalid("amount_over_limit",
                    "amount is above your limit per payment of " + Money.format(settings.limitPerPayment()) + " ETB.",
                    "amount");
        }
        String returnUrl = required(r.returnUrl(), "returnUrl");
        if (!settings.returnUrlAllowlist().contains(returnUrl)) {
            throw ApiException.invalid("return_url_not_registered",
                    "returnUrl must exactly match a URL registered for your account.", "returnUrl");
        }
        if (r.webhookUrl() != null && !r.webhookUrl().equals(settings.webhookUrl())) {
            throw ApiException.invalid("webhook_url_not_registered",
                    "webhookUrl must match the webhook URL registered for your account.", "webhookUrl");
        }
        String webhookUrl = r.webhookUrl() != null ? r.webhookUrl() : settings.webhookUrl();

        try {
            return new NewPayment(orderReference, amount, "ETB", r.description(), returnUrl, webhookUrl, expiresIn,
                    json.writeValueAsString(metadata));
        } catch (JsonProcessingException e) {
            throw ApiException.invalid("metadata_invalid", "metadata could not be read.", "metadata");
        }
    }

    private static String required(String value, String param) {
        if (value == null || value.isBlank()) {
            throw ApiException.invalid("parameter_missing", param + " is required.", param);
        }
        return value;
    }
}
