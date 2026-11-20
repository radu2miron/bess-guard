package edu.tucn.bessguard.model.dto;

import edu.tucn.bessguard.model.ExecutionOutcome;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
public record ExecutionOutcomeRequest(ExecutionOutcome outcome, double deliveredEnergyKwh) {
}
