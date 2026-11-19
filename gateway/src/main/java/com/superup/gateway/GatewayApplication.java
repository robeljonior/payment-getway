package com.superup.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The gateway service: layer 1 of docs/02-architecture.md ("The four layers"). It answers one question:
 * is the request real and allowed? It checks API keys, rate limits, the request itself and idempotency
 * keys, then hands the payment to the orchestrator service (layer 2, written in Go, in ../orchestrator).
 * <ul>
 *   <li>{@code gateway}: the checks</li>
 *   <li>{@code clients}: part 5, the clients (merchants), their settings and keys</li>
 *   <li>{@code orchestrator}: the client for the orchestrator service</li>
 *   <li>{@code api}: the Payment API endpoints</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
