package edu.tucn.bessguard.controller;

import com.fasterxml.jackson.databind.JsonNode;
import edu.tucn.bessguard.model.BessGuardOperation;
import edu.tucn.bessguard.model.OperatingCertificate;
import edu.tucn.bessguard.service.AuthorizedBlockchainService;
import edu.tucn.bessguard.service.BessGuardPayloads;
import edu.tucn.bessguard.service.SimulatedGatewayService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.*;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 * Certifier -> blockchain; the gateway is fenced after a committed QUIESCING transition.
 */
@RestController
@RequestMapping("/api/certificates")
@SecurityRequirement(name = "Bearer Authentication")
public class CertificateController {

    private final AuthorizedBlockchainService blockchain;
    private final SimulatedGatewayService gateway;

    public CertificateController(AuthorizedBlockchainService blockchain, SimulatedGatewayService gateway) {
        this.blockchain = blockchain;
        this.gateway = gateway;
    }

    @PostMapping("/initial")
    public JsonNode issueInitial(@RequestBody OperatingCertificate certificate) {
        return blockchain.submit(BessGuardOperation.ISSUE_INITIAL_CERTIFICATE,
                BessGuardPayloads.certificate(certificate));
    }

    @PostMapping("/recertification")
    public JsonNode beginRecertification(@RequestBody OperatingCertificate certificate) {
        JsonNode committed = blockchain.submit(BessGuardOperation.BEGIN_RECERTIFICATION,
                BessGuardPayloads.certificate(certificate));
        gateway.freeze(certificate.getBatteryId());
        return committed;
    }

    @PostMapping("/{batteryId}/retry-quiescence")
    public JsonNode retryQuiescence(@PathVariable String batteryId) {
        return blockchain.submit(BessGuardOperation.RETRY_QUIESCENCE, batteryId);
    }

    @PostMapping("/{batteryId}/activate")
    public JsonNode activate(@PathVariable String batteryId) {
        return blockchain.submit(BessGuardOperation.ACTIVATE_CERTIFICATE, batteryId);
    }

    @GetMapping
    public JsonNode list() {
        return blockchain.read(BessGuardOperation.GET_ALL_CERTIFICATES);
    }

    @GetMapping("/{batteryId}/{version}")
    public JsonNode read(@PathVariable String batteryId, @PathVariable String version) {
        return blockchain.read(BessGuardOperation.READ_CERTIFICATE, batteryId, version);
    }
}
