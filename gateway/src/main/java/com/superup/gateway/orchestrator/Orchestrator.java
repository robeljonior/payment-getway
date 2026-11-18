package com.superup.gateway.orchestrator;

import com.superup.gateway.gateway.Caller;
import java.util.Optional;

/**
 * Layer 2, the orchestrator, is its own service, written in Go (see ../orchestrator). The gateway only
 * checks requests; the orchestrator owns every payment, the ledger and the events. Answers are the
 * PaymentSession JSON of the Payment API, passed on to the client as they are.
 */
public interface Orchestrator {

    /**
     * Creates a payment. The create key makes this safe to repeat: the same key always gives the same
     * payment, so a retry after a timeout never makes two.
     */
    String create(Caller caller, String createKey, NewPayment payment);

    /** Reads one payment, only for its own client and mode. Empty when there is none. */
    Optional<String> find(Caller caller, String sessionId);

    /** The newest payment for the client's own order reference. Empty when there is none. */
    Optional<String> latestForOrder(Caller caller, String orderReference);
}
