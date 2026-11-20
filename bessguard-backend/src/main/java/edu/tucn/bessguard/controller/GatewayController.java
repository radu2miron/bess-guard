package edu.tucn.bessguard.controller;

import com.fasterxml.jackson.databind.JsonNode;
import edu.tucn.bessguard.model.BessGuardOperation;
import edu.tucn.bessguard.model.dto.EnergyReportRequest;
import edu.tucn.bessguard.model.dto.GatewayActivationRequest;
import edu.tucn.bessguard.security.RoleAuthorizationService;
import edu.tucn.bessguard.service.AuthorizedBlockchainService;
import edu.tucn.bessguard.service.SimulatedGatewayService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 * Dispatch gateway and local BMS simulator; no direct public authorizeExecution shortcut.
 */
@RestController
@RequestMapping("/api/gateway")
@SecurityRequirement(name = "Bearer Authentication")
public class GatewayController {

    private final SimulatedGatewayService gateway;
    private final AuthorizedBlockchainService blockchain;
    private final RoleAuthorizationService roles;

    public GatewayController(SimulatedGatewayService gateway, AuthorizedBlockchainService blockchain,
                             RoleAuthorizationService roles) {
        this.gateway = gateway;
        this.blockchain = blockchain;
        this.roles = roles;
    }

    @PostMapping("/batteries/{batteryId}/quiescence/ack")
    public JsonNode acknowledgeQuiescence(@PathVariable String batteryId) {
        roles.authorize(BessGuardOperation.ACKNOWLEDGE_QUIESCENCE);
        return gateway.acknowledgeQuiescence(batteryId);
    }

    @PostMapping("/batteries/{batteryId}/activate")
    public JsonNode activate(@PathVariable String batteryId, @RequestBody GatewayActivationRequest request) {
        roles.authorize(BessGuardOperation.ACTIVATE_GATEWAY_VERSION);
        return gateway.activate(batteryId, request);
    }

    @PostMapping("/batteries/{batteryId}/energy")
    public JsonNode reportAvailableEnergy(@PathVariable String batteryId, @RequestBody EnergyReportRequest request) {
        roles.authorize(BessGuardOperation.REPORT_AVAILABLE_ENERGY);
        return gateway.updateEnergy(batteryId, request.availableEnergyKwh());
    }

    @PostMapping("/batteries/{batteryId}/suspend")
    public JsonNode suspend(@PathVariable String batteryId) {
        roles.authorize(BessGuardOperation.SUSPEND_DISPATCH);
        return gateway.suspend(batteryId);
    }

    @PostMapping("/batteries/{batteryId}/reconcile")
    public JsonNode reconcile(@PathVariable String batteryId) {
        roles.authorize(BessGuardOperation.ACTIVATE_GATEWAY_VERSION);
        return gateway.reconcile(batteryId);
    }

    @GetMapping("/batteries/{batteryId}")
    public Map<String, Object> localState(@PathVariable String batteryId) {
        roles.authorize(BessGuardOperation.READ_COORDINATION_STATE);
        return gateway.localState(batteryId);
    }

    @PostMapping("/dispatch/{commandId}/execute")
    public Map<String, Object> executeSimulated(@PathVariable String commandId) {
        roles.authorize(BessGuardOperation.AUTHORIZE_EXECUTION);
        return gateway.execute(commandId);
    }

    @GetMapping("/dispatch/{commandId}/attempts/{attemptId}")
    public JsonNode readAttempt(@PathVariable String commandId, @PathVariable String attemptId) {
        return blockchain.read(BessGuardOperation.READ_EXECUTION_ATTEMPT, commandId, attemptId);
    }

    @GetMapping("/attempts")
    public JsonNode listAttempts() {
        return blockchain.read(BessGuardOperation.GET_ALL_EXECUTION_ATTEMPTS);
    }
}
