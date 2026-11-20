package edu.tucn.bessguard.controller;

import edu.tucn.bessguard.model.dto.PeakShavingRequest;
import edu.tucn.bessguard.model.dto.PeakShavingResponse;
import edu.tucn.bessguard.service.EnergyManagementService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 * EMS scheduling API: a minimal peak-shaving coordinator, not a physical controller.
 */
@RestController
@RequestMapping("/api/ems")
@SecurityRequirement(name = "Bearer Authentication")
public class EnergyManagementController {

    private final EnergyManagementService ems;

    public EnergyManagementController(EnergyManagementService ems) {
        this.ems = ems;
    }

    @PostMapping("/peak-shaving")
    public PeakShavingResponse plan(@RequestBody PeakShavingRequest request) {
        return ems.planPeakShaving(request);
    }
}
