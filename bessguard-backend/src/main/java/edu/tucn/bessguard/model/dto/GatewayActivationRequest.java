package edu.tucn.bessguard.model.dto;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
public record GatewayActivationRequest(int version, double availableEnergyKwh) {
}
