package edu.tucn.bessguard.contract;

import edu.tucn.bessguard.model.*;
import edu.tucn.bessguard.util.JsonMapper;
import org.hyperledger.fabric.contract.ClientIdentity;
import org.hyperledger.fabric.contract.Context;
import org.hyperledger.fabric.shim.ChaincodeStub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.security.auth.x500.X500Principal;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * In-memory stub tests; a Fabric peer test is still required for actual MVCC conflict behavior.
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
class BessGuardContractTest {
    private final Map<String, String> world = new HashMap<>();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2027-01-01T00:00:00Z"));
    private BessGuardContract facade;
    private Context ctx;

    @BeforeEach
    void setup() {
        world.clear();
        now.set(Instant.parse("2027-01-01T00:00:00Z"));
        ctx = mock(Context.class);
        ChaincodeStub stub = mock(ChaincodeStub.class);
        ClientIdentity identity = mock(ClientIdentity.class);
        when(ctx.getStub()).thenReturn(stub);
        when(ctx.getClientIdentity()).thenReturn(identity);
        when(identity.getMSPID()).thenReturn("Org1MSP");
        when(identity.getId()).thenReturn("backend-service-identity");
        X509Certificate serviceCertificate = mock(X509Certificate.class);
        when(serviceCertificate.getSubjectX500Principal()).thenReturn(
                new X500Principal("CN=User1@org1.example.com, OU=client, O=Org1"));
        when(identity.getX509Certificate()).thenReturn(serviceCertificate);
        when(stub.getTxTimestamp()).thenAnswer(a -> now.get());
        when(stub.getStringState(anyString())).thenAnswer(a -> world.get(a.getArgument(0)));
        doAnswer(a -> {
            world.put(a.getArgument(0), a.getArgument(1));
            return null;
        })
                .when(stub).putStringState(anyString(), anyString());
        facade = new BessGuardContract();
    }

    private String cert(int version, int kw) {
        return "{\"docType\":\"OPERATING_CERTIFICATE\",\"batteryId\":\"bat1\","
                + "\"version\":" + version + ",\"maxDischargePowerKw\":" + kw + ","
                + "\"minEnergyKwh\":2,\"maxEnergyKwh\":20,"
                + "\"validFrom\":\"2026-12-01T00:00:00Z\",\"validUntil\":\"2027-12-01T00:00:00Z\"}";
    }

    private String dispatch(String id, int version, int power, String start, String end) {
        return "{\"docType\":\"DISPATCH_COMMAND\",\"id\":\"" + id + "\","
                + "\"batteryId\":\"bat1\",\"certificateVersion\":" + version + ","
                + "\"requestedPowerKw\":" + power + ",\"startTime\":\"" + start + "\","
                + "\"endTime\":\"" + end + "\",\"expiresAt\":\"2027-01-01T23:00:00Z\"}";
    }

    private void init() {
        facade.registerBess(ctx, "{\"docType\":\"SECOND_LIFE_BESS\",\"id\":\"bess1\",\"name\":\"Test BESS\"}");
        facade.registerBattery(ctx, "{\"docType\":\"BATTERY_UNIT\",\"id\":\"bat1\",\"bessId\":\"bess1\","
                + "\"serialNumber\":\"SN1\",\"nominalCapacityKwh\":22}");
        facade.issueInitialCertificate(ctx, cert(1, 10));
        facade.activateGatewayVersion(ctx, "bat1", "1", "15");
    }

    private BatteryCoordinationState state() {
        return JsonMapper.fromJson(facade.readBatteryCoordinationState(ctx, "bat1"), BatteryCoordinationState.class);
    }

    private DispatchCommand command(String id) {
        return JsonMapper.fromJson(facade.readDispatch(ctx, id), DispatchCommand.class);
    }

    @Test
    void initialCertificateNeedsGatewayActivation() {
        facade.registerBess(ctx, "{\"id\":\"bess1\",\"name\":\"Test BESS\"}");
        facade.registerBattery(ctx, "{\"id\":\"bat1\",\"bessId\":\"bess1\",\"serialNumber\":\"SN1\",\"nominalCapacityKwh\":20}");
        facade.issueInitialCertificate(ctx, cert(1, 10));
        assertEquals(CoordinationMode.UPDATED, state().getMode());
        assertThrows(IllegalArgumentException.class, () -> facade.reserveDispatch(ctx,
                dispatch("cmd1", 1, 5, "2027-01-01T01:00:00Z", "2027-01-01T02:00:00Z")));
        facade.activateGatewayVersion(ctx, "bat1", "1", "15");
        assertEquals(CoordinationMode.ACTIVE, state().getMode());
    }

    @Test
    void rejectsOverlappingPowerAndInsufficientEnergy() {
        init();
        facade.reserveDispatch(ctx, dispatch("cmd1", 1, 7, "2027-01-01T01:00:00Z", "2027-01-01T02:00:00Z"));
        assertThrows(IllegalArgumentException.class, () -> facade.reserveDispatch(ctx,
                dispatch("cmd2", 1, 4, "2027-01-01T01:30:00Z", "2027-01-01T02:30:00Z")));
        assertThrows(IllegalArgumentException.class, () -> facade.reserveDispatch(ctx,
                dispatch("cmd3", 1, 7, "2027-01-01T03:00:00Z", "2027-01-01T04:00:00Z")));
        facade.reserveDispatch(ctx, dispatch("cmd4", 1, 3, "2027-01-01T03:00:00Z", "2027-01-01T04:00:00Z"));
        assertEquals(DispatchStatus.RESERVED, command("cmd4").getStatus());
    }

    @Test
    void recertificationFencesOldVersionAndCancelsPending() {
        init();
        facade.reserveDispatch(ctx, dispatch("cmd1", 1, 9, "2027-01-01T01:00:00Z", "2027-01-01T02:00:00Z"));
        facade.beginRecertification(ctx, cert(2, 6));
        assertEquals(CoordinationMode.QUIESCING, state().getMode());
        assertThrows(IllegalArgumentException.class, () -> facade.reserveDispatch(ctx,
                dispatch("cmd2", 1, 5, "2027-01-01T03:00:00Z", "2027-01-01T04:00:00Z")));
        facade.acknowledgeQuiescence(ctx, "bat1");
        assertEquals(DispatchStatus.CANCELLED, command("cmd1").getStatus());
        facade.activateCertificate(ctx, "bat1");
        assertEquals(CoordinationMode.UPDATED, state().getMode());
        facade.activateGatewayVersion(ctx, "bat1", "2", "15");
        assertThrows(IllegalArgumentException.class, () -> facade.reserveDispatch(ctx,
                dispatch("cmd3", 1, 5, "2027-01-01T03:00:00Z", "2027-01-01T04:00:00Z")));
        assertThrows(IllegalArgumentException.class, () -> facade.reserveDispatch(ctx,
                dispatch("cmd4", 2, 9, "2027-01-01T03:00:00Z", "2027-01-01T04:00:00Z")));
        facade.reserveDispatch(ctx, dispatch("cmd5", 2, 5, "2027-01-01T03:00:00Z", "2027-01-01T04:00:00Z"));
        assertEquals(2, state().getActiveCertificateVersion());
    }

    @Test
    void cannotQuiesceWhileDispatchedOperationIsUnsettled() {
        init();
        facade.reserveDispatch(ctx, dispatch("cmd1", 1, 5, "2027-01-01T01:00:00Z", "2027-01-01T02:00:00Z"));
        now.set(Instant.parse("2027-01-01T01:05:00Z"));
        ExecutionAttempt attempt = JsonMapper.fromJson(facade.authorizeExecution(ctx, "cmd1", "a1"), ExecutionAttempt.class);
        assertEquals(Decision.ACCEPTED, attempt.getDecision());
        facade.beginRecertification(ctx, cert(2, 6));
        assertThrows(IllegalArgumentException.class, () -> facade.acknowledgeQuiescence(ctx, "bat1"));
        facade.reportExecutionOutcome(ctx, "cmd1", "a1", "COMPLETED", "2.5");
        facade.acknowledgeQuiescence(ctx, "bat1");
        assertEquals(CoordinationMode.QUIESCING, state().getMode());
    }

    @Test
    void prematureAttemptDoesNotDestroyFutureReservation() {
        init();
        facade.reserveDispatch(ctx, dispatch("cmd1", 1, 4, "2027-01-01T01:00:00Z", "2027-01-01T02:00:00Z"));
        ExecutionAttempt attempt = JsonMapper.fromJson(facade.authorizeExecution(ctx, "cmd1", "too-early"), ExecutionAttempt.class);
        assertEquals(Decision.REJECTED, attempt.getDecision());
        assertEquals(DispatchStatus.RESERVED, command("cmd1").getStatus());
        now.set(Instant.parse("2027-01-01T01:05:00Z"));
        assertEquals(Decision.ACCEPTED,
                JsonMapper.fromJson(facade.authorizeExecution(ctx, "cmd1", "on-time"), ExecutionAttempt.class).getDecision());
        assertThrows(IllegalArgumentException.class, () -> facade.authorizeExecution(ctx, "cmd1", "on-time"));
    }

    @Test
    void energyDropSuspendsDispatchWhenOutstandingBudgetIsInsufficient() {
        init();
        facade.reserveDispatch(ctx, dispatch("cmd1", 1, 7, "2027-01-01T01:00:00Z", "2027-01-01T02:00:00Z"));
        facade.reportAvailableEnergy(ctx, "bat1", "8"); // 8 - min(2) < reserved(7)
        assertEquals(CoordinationMode.SUSPENDED, state().getMode());
        assertThrows(IllegalArgumentException.class, () -> facade.reserveDispatch(ctx,
                dispatch("cmd2", 1, 1, "2027-01-01T03:00:00Z", "2027-01-01T04:00:00Z")));
    }

    @Test
    void unauthorizedMspIsDenied() {
        when(ctx.getClientIdentity().getMSPID()).thenReturn("Org2MSP");
        assertThrows(SecurityException.class, () -> facade.registerBess(ctx,
                "{\"id\":\"bess1\",\"name\":\"Test BESS\"}"));
    }

    @Test
    void unauthorizedCertificateSubjectIsDenied() {
        X509Certificate untrusted = mock(X509Certificate.class);
        when(untrusted.getSubjectX500Principal()).thenReturn(
                new X500Principal("CN=Other@org1.example.com"));
        when(ctx.getClientIdentity().getX509Certificate()).thenReturn(untrusted);
        assertThrows(SecurityException.class, () -> facade.registerBess(ctx,
                "{\"id\":\"bess1\",\"name\":\"Test BESS\"}"));
    }
}
