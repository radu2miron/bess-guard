package edu.tucn.bessguard.model;


/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 * BESSGuard domain model.
 */
public enum DispatchStatus {
    PENDING,
    RESERVED,
    DISPATCHED,
    COMPLETED,
    CANCELLED,
    REJECTED,
    EXPIRED,
    FAILED
}
