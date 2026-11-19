package com.superup.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superup.gateway.common.Ids;
import com.superup.gateway.common.Money;
import com.superup.gateway.gateway.ApiException;
import com.superup.gateway.gateway.Caller;
import com.superup.gateway.orchestrator.NewPayment;
import com.superup.gateway.orchestrator.Orchestrator;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;

/**
 * An orchestrator kept in memory, for the gateway's tests. Like the real one, the same create key
 * always gives the same payment, and a client only sees its own payments in its own mode.
 */
@TestComponent
@Primary
public class FakeOrchestrator implements Orchestrator {

    public record Call(Caller caller, String createKey, NewPayment payment) {
    }

    private record Stored(String merchantId, String mode, String orderReference, String body) {
    }

    private final ObjectMapper json;
    private final Map<String, Stored> byCreateKey = new ConcurrentHashMap<>();
    private final Map<String, Stored> byId = new ConcurrentHashMap<>();
    private final List<Call> creates = new ArrayList<>();
    private volatile boolean failNext;

    public FakeOrchestrator(ObjectMapper json) {
        this.json = json;
    }

    public void failNextCall() {
        failNext = true;
    }

    public synchronized Call lastCreate() {
        return creates.get(creates.size() - 1);
    }

    /** How many different payments a client has. */
    public long createsFor(String merchantId) {
        return byId.values().stream().filter(s -> s.merchantId().equals(merchantId)).count();
    }

    @Override
    public synchronized String create(Caller caller, String createKey, NewPayment p) {
        creates.add(new Call(caller, createKey, p));
        if (failNext) {
            failNext = false;
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "api_error", "service_unavailable",
                    "Payments are not available right now.", null);
        }
        return byCreateKey.computeIfAbsent(createKey, k -> {
            String id = Ids.newId("ps_");
            ObjectNode session = json.createObjectNode()
                    .put("id", id)
                    .put("mode", caller.mode())
                    .put("status", "CREATED")
                    .put("orderReference", p.orderReference())
                    .put("amount", Money.format(p.amount()))
                    .put("currency", p.currency())
                    .put("amountRefunded", "0.00")
                    .putNull("fee")
                    .putNull("net")
                    .put("checkoutUrl", "https://checkout.test/pay/" + id);
            try {
                session.set("metadata", json.readTree(p.metadataJson()));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            Stored stored = new Stored(caller.merchantId(), caller.mode(), p.orderReference(), session.toString());
            byId.put(id, stored);
            return stored;
        }).body();
    }

    @Override
    public Optional<String> find(Caller caller, String sessionId) {
        return Optional.ofNullable(byId.get(sessionId)).filter(s -> mine(s, caller)).map(Stored::body);
    }

    @Override
    public Optional<String> latestForOrder(Caller caller, String orderReference) {
        return byId.values().stream()
                .filter(s -> mine(s, caller) && s.orderReference().equals(orderReference))
                .reduce((a, b) -> b).map(Stored::body);
    }

    private static boolean mine(Stored s, Caller caller) {
        return s.merchantId().equals(caller.merchantId()) && s.mode().equals(caller.mode());
    }
}
