package edu.tucn.bessguard.controller;

import com.fasterxml.jackson.databind.JsonNode;
import edu.tucn.bessguard.model.BessGuardOperation;
import edu.tucn.bessguard.model.SecondLifeBESS;
import edu.tucn.bessguard.service.AuthorizedBlockchainService;
import edu.tucn.bessguard.service.BessGuardPayloads;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.*;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 * Second-life BESS resource endpoints;
 */
@RestController
@RequestMapping("/api/bess")
@SecurityRequirement(name = "Bearer Authentication")
public class BessController {

    private final AuthorizedBlockchainService blockchain;

    public BessController(AuthorizedBlockchainService blockchain) {
        this.blockchain = blockchain;
    }

    @PostMapping
    public JsonNode register(@RequestBody SecondLifeBESS value) {
        return blockchain.submit(BessGuardOperation.REGISTER_BESS, BessGuardPayloads.bess(value));
    }

    @GetMapping
    public JsonNode list() {
        return blockchain.read(BessGuardOperation.GET_ALL_BESS);
    }

    @GetMapping("/{id}")
    public JsonNode read(@PathVariable String id) {
        return blockchain.read(BessGuardOperation.READ_BESS, id);
    }
}
