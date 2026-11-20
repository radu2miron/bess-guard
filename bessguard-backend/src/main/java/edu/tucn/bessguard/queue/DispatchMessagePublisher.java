package edu.tucn.bessguard.queue;

import edu.tucn.bessguard.model.DispatchMessage;
import lombok.extern.log4j.Log4j2;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
@Service
@Log4j2
public class DispatchMessagePublisher {

    private final RabbitTemplate rabbitTemplate;

    public DispatchMessagePublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publish(DispatchMessage message) {
        log.info("Queueing committed dispatch command {}", message.commandId());
        rabbitTemplate.convertAndSend(RabbitConfig.DISPATCH_QUEUE, message);
    }
}
