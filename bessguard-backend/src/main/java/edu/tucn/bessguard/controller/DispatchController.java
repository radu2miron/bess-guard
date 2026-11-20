package edu.tucn.bessguard.controller;

import com.fasterxml.jackson.databind.JsonNode;
import edu.tucn.bessguard.model.BessGuardOperation;
import edu.tucn.bessguard.model.DeliveryStatus;
import edu.tucn.bessguard.model.DispatchCommand;
import edu.tucn.bessguard.model.DispatchSubmissionResponse;
import edu.tucn.bessguard.security.RoleAuthorizationService;
import edu.tucn.bessguard.service.AuthorizedBlockchainService;
import edu.tucn.bessguard.service.DeliveryStatusService;
import edu.tucn.bessguard.service.EnergyManagementService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.*;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 * EMS -> committed reservation -> message broker. Only one dispatch submission API exists.
 */
@RestController
@RequestMapping("/api/dispatch")
@SecurityRequirement(name = "Bearer Authentication")
public class DispatchController {

    private final EnergyManagementService ems;
    private final AuthorizedBlockchainService blockchain;
    private final DeliveryStatusService statuses;
    private final RoleAuthorizationService roles;

    public DispatchController(EnergyManagementService ems, AuthorizedBlockchainService blockchain,
                              DeliveryStatusService statuses, RoleAuthorizationService roles) {
        this.ems = ems;
        this.blockchain = blockchain;
        this.statuses = statuses;
        this.roles = roles;
    }

    @PostMapping
    public DispatchSubmissionResponse reserve(@RequestBody DispatchCommand command) {
        return ems.reserve(command);
    }

    @PostMapping("/{commandId}/redeliver")
    public DispatchSubmissionResponse redeliver(@PathVariable String commandId) {
        return ems.redeliver(commandId);
    }

    @PostMapping("/{commandId}/cancel")
    public JsonNode cancel(@PathVariable String commandId) {
        return blockchain.submit(BessGuardOperation.CANCEL_DISPATCH, commandId);
    }

    @GetMapping
    public JsonNode list() {
        return blockchain.read(BessGuardOperation.GET_ALL_DISPATCHES);
    }

    @GetMapping("/{commandId}")
    public JsonNode read(@PathVariable String commandId) {
        return blockchain.read(BessGuardOperation.READ_DISPATCH, commandId);
    }

    @GetMapping("/{commandId}/delivery")
    public DeliveryStatus delivery(@PathVariable String commandId) {
        roles.authorize(BessGuardOperation.READ_DISPATCH);
        return statuses.get(commandId);
    }
}
