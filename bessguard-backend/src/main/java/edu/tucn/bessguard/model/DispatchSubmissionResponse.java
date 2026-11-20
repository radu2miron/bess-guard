package edu.tucn.bessguard.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 * A confirmed reservation plus the separate RabbitMQ delivery result.
 */
public record DispatchSubmissionResponse(JsonNode reservation, String deliveryState, String detail) {
}
