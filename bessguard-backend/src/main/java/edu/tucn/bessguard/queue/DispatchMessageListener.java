package edu.tucn.bessguard.queue;

import edu.tucn.bessguard.model.DispatchMessage;
import edu.tucn.bessguard.service.DeliveryStatusService;
import edu.tucn.bessguard.service.SimulatedGatewayService;
import lombok.extern.log4j.Log4j2;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 * Message broker -> dispatch gateway. It never submits new reservations or calls Fabric
 * as the authenticated HTTP user. Duplicate deliveries are idempotent at the gateway.
 */
@Component
@Log4j2
public class DispatchMessageListener {

    private final SimulatedGatewayService gateway;
    private final DeliveryStatusService statuses;

    public DispatchMessageListener(SimulatedGatewayService gateway, DeliveryStatusService statuses) {
        this.gateway = gateway;
        this.statuses = statuses;
    }

    @RabbitListener(queues = RabbitConfig.DISPATCH_QUEUE, concurrency = "${rabbit.consumer.concurrency:50}")
    public void receiveDispatch(DispatchMessage message) {
        long receivedAt = System.currentTimeMillis();

        try {
            boolean staged = gateway.stage(message);
            String state = staged ? "STAGED" : "IGNORED";
            statuses.update(message.commandId(), state,
                    staged ? "Queued command received by gateway." : "Command is no longer eligible for delivery.");
            log.info("[DELIVERY_TIME]{};{};{};{};{}", message.commandId(), message.publishedAt(),
                    receivedAt, receivedAt - message.publishedAt(), state);
        } catch (RuntimeException e) {
            statuses.update(message.commandId(), "FAILED", e.getMessage());
            log.error("Gateway delivery failed for {}", message.commandId(), e);
            // Retain original sample semantics: business exceptions are logged, not automatically requeued.
            // A committed RESERVED command may be explicitly re-delivered via the REST API.
        }
    }
}
