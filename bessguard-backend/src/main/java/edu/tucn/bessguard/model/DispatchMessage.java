package edu.tucn.bessguard.model;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 * RabbitMQ delivers a committed dispatch reference, not a Fabric transaction.
 * Repeated delivery is safe because the gateway re-reads authoritative ledger state.
 */
public record DispatchMessage(String commandId, String batteryId, long publishedAt) {
}
