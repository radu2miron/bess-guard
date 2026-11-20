package edu.tucn.bessguard.model.dto;

import java.util.List;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 * Requested aggregate discharge for a future dispatch interval.
 */
public record PeakShavingRequest(List<String> batteryIds, String startTime, String endTime,
                                 String expiresAt, double requestedPowerKw) {
}
