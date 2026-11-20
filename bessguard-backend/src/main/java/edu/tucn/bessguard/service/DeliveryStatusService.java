package edu.tucn.bessguard.service;

import edu.tucn.bessguard.model.DeliveryStatus;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
@Service
public class DeliveryStatusService {

    private final ConcurrentMap<String, DeliveryStatus> statuses = new ConcurrentHashMap<>();

    public void update(String commandId, String state, String detail) {
        statuses.compute(commandId, (id, previous) -> {

            if (previous != null && "EXECUTED".equals(previous.state())) {
                return previous;
            }

            if (previous != null && "QUEUED".equals(state) && "STAGED".equals(previous.state())) {
                return previous;
            }

            return new DeliveryStatus(id, state, detail, System.currentTimeMillis());
        });
    }

    public DeliveryStatus get(String commandId) {
        return statuses.getOrDefault(commandId, new DeliveryStatus(commandId, "UNKNOWN",
                "In-memory delivery tracking is not persistent; query the ledger for dispatch status.",
                System.currentTimeMillis()));
    }

    public Map<String, DeliveryStatus> snapshot() {
        return Map.copyOf(statuses);
    }
}
