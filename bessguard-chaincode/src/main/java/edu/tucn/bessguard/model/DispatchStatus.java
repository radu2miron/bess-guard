package edu.tucn.bessguard.model;


/**
 * BESSGuard domain model.
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
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
