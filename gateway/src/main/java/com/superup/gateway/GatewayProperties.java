package com.superup.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings from application.properties, under "gateway.". */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
        OrchestratorSettings orchestrator,
        RateLimit rateLimit) {

    /** Where the orchestrator service is, and the shared secret it expects. */
    public record OrchestratorSettings(String url, String token, int timeoutMs) {
        public OrchestratorSettings {
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("Set gateway.orchestrator.url (ORCHESTRATOR_URL)");
            }
            if (token == null || token.length() < 32) {
                throw new IllegalArgumentException(
                        "Set gateway.orchestrator.token (INTERNAL_TOKEN) to a random secret of at least 32 characters");
            }
        }
    }

    /** Requests per second. Test and live keys are counted separately because they are separate keys. */
    public record RateLimit(
            int perKeyPerSecond,
            int perKeyBurst,
            int createSessionPerSecond,
            int perIpPerSecond) {
    }
}
