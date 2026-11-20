package edu.tucn.bessguard.controller;

import com.fasterxml.jackson.databind.JsonNode;
import edu.tucn.bessguard.model.BatteryUnit;
import edu.tucn.bessguard.model.BessGuardOperation;
import edu.tucn.bessguard.model.dto.AssignmentRequest;
import edu.tucn.bessguard.service.AuthorizedBlockchainService;
import edu.tucn.bessguard.service.BessGuardPayloads;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.*;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
@RestController
@RequestMapping("/api/batteries")
@SecurityRequirement(name = "Bearer Authentication")
public class BatteryController {

    private final AuthorizedBlockchainService blockchain;

    public BatteryController(AuthorizedBlockchainService blockchain) {
        this.blockchain = blockchain;
    }

    @PostMapping
    public JsonNode register(@RequestBody BatteryUnit battery) {
        return blockchain.submit(BessGuardOperation.REGISTER_BATTERY, BessGuardPayloads.battery(battery));
    }

    @PutMapping("/{batteryId}/assignment")
    public JsonNode assign(@PathVariable String batteryId, @RequestBody AssignmentRequest request) {
        return blockchain.submit(BessGuardOperation.ASSIGN_BATTERY, batteryId, request.bessId());
    }

    @GetMapping
    public JsonNode list() {
        return blockchain.read(BessGuardOperation.GET_ALL_BATTERIES);
    }

    @GetMapping("/{batteryId}")
    public JsonNode read(@PathVariable String batteryId) {
        return blockchain.read(BessGuardOperation.READ_BATTERY, batteryId);
    }

    @GetMapping("/{batteryId}/state")
    public JsonNode state(@PathVariable String batteryId) {
        return blockchain.read(BessGuardOperation.READ_COORDINATION_STATE, batteryId);
    }
}
