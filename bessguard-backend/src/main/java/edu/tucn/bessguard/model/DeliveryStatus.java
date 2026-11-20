package edu.tucn.bessguard.model;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 * Best-effort, in-memory delivery diagnostics; never a ledger execution outcome.
 */
public record DeliveryStatus(String commandId, String state, String detail, long updatedAt) {
}
