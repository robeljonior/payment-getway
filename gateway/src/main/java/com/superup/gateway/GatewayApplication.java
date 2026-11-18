package com.superup.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The payment gateway, built in four layers (docs/02-architecture.md, "The four layers"):
 * <ol>
 *   <li>{@code gateway}: is the request real and allowed?</li>
 *   <li>{@code orchestrator}: what happens next?</li>
 *   <li>{@code connectors}: talk to each bank or wallet</li>
 *   <li>{@code record}: the ledger and the database</li>
 * </ol>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
