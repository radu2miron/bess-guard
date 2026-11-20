package edu.tucn.bessguard.model.dto;

import edu.tucn.bessguard.model.DispatchSubmissionResponse;

import java.util.List;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 * Best-effort greedy scheduling; each individual reservation is committed independently.
 */
public record PeakShavingResponse(double requestedPowerKw, double reservedPowerKw,
                                  double unmetPowerKw, List<DispatchSubmissionResponse> reservations,
                                  List<String> skippedBatteries) {
}
