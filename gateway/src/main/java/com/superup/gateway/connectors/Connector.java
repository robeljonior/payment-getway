package com.superup.gateway.connectors;

/**
 * Layer 3: one connector per kind of bank or wallet API (docs/03-banks-and-connectors.md).
 * A connector turns the provider's answer into our standard result. It never decides success
 * on its own and never writes to the ledger: the orchestrator does both.
 *
 * <p>This is the contract. The test providers (push, OTP, redirect) are the next build step.
 */
public interface Connector {

    /** The provider codes this connector serves, for example TELEBIRR. */
    String providerType();

    /** Starts collecting money for one try. Our attempt ID is the reference we send. */
    CollectionResult initiateCollection(CollectionRequest request);

    /** Asks the provider for the real status. A callback alone is never trusted. */
    CollectionResult getCollectionStatus(String attemptId, String providerRef);

    record CollectionRequest(String attemptId, long amountSantim, String currency, String payer, String description) {
    }

    /** Our standard answer, whatever the provider said. */
    record CollectionResult(Outcome outcome, String providerRef, String nextAction, String providerCode) {
    }

    enum Outcome { ACTION_REQUIRED, PROCESSING, PENDING, SUCCEEDED, FAILED }
}
