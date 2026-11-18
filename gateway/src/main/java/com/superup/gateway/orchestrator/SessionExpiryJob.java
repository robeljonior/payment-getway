package com.superup.gateway.orchestrator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Closes payments whose time ran out. A payment with a try still running is skipped,
 * because money may be moving (the database also refuses it).
 */
@Component
@ConditionalOnProperty(name = "gateway.expiry-job.enabled", havingValue = "true")
public class SessionExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(SessionExpiryJob.class);

    private final PaymentService payments;

    public SessionExpiryJob(PaymentService payments) {
        this.payments = payments;
    }

    @Scheduled(fixedDelayString = "${gateway.expiry-job.interval-ms}")
    public void run() {
        for (String sessionId : payments.dueForExpiry(100)) {
            try {
                payments.endPayment(sessionId, "EXPIRED");
            } catch (DataAccessException e) {
                // A try started at the same moment. It will be checked again next time.
                log.info("Skipped expiry of {}: {}", sessionId, e.getMostSpecificCause().getMessage());
            }
        }
    }
}
