package edu.tucn.bessguard.contract;

import edu.tucn.bessguard.util.JsonMapper;
import org.hyperledger.fabric.contract.Context;
import org.hyperledger.fabric.contract.ContractInterface;
import org.hyperledger.fabric.contract.annotation.Contract;
import org.hyperledger.fabric.contract.annotation.Default;
import org.hyperledger.fabric.contract.annotation.Transaction;

/**
 * Fabric-facing facade ONLY. Domain, admission and recertification logic live in services,
 * following the DppProvVerContract structure supplied as the reference project.
 */
@Contract(name = "BessGuardContract")
@Default
public class BessGuardContract implements ContractInterface {
    private final BessService bessService = new BessService();
    private final BatteryService batteryService = new BatteryService();
    private final CertificateService certificateService = new CertificateService();
    private final DispatchService dispatchService = new DispatchService();
    private final GatewayService gatewayService = new GatewayService();
    private final ExecutionService executionService = new ExecutionService();

    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public String registerBess(Context ctx, String json) {
        return bessService.register(ctx, json);
    }

    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public String registerBattery(Context ctx, String json) {
        return batteryService.register(ctx, json);
    }

    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public String assignBatteryToBess(Context ctx, String batteryId, String bessId) {
        return batteryService.assign(ctx, batteryId, bessId);
    }

    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public String issueInitialCertificate(Context ctx, String json) {
        return certificateService.issueInitial(ctx, json);
    }

    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public String reserveDispatch(Context ctx, String json) {
        return dispatchService.reserve(ctx, json);
    }

    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public String cancelDispatch(Context ctx, String commandId) {
        return dispatchService.cancel(ctx, commandId);
    }

    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public String beginRecertification(Context ctx, String json) {
        return certificateService.beginRecertification(ctx, json);
    }

    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public String acknowledgeQuiescence(Context ctx, String batteryId) {
        return certificateService.acknowledgeQuiescence(ctx, batteryId);
    }

    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public String retryQuiescence(Context ctx, String batteryId) {
        return certificateService.retryQuiescence(ctx, batteryId);
    }

    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public String activateCertificate(Context ctx, String batteryId) {
        return certificateService.activateCertificate(ctx, batteryId);
    }

    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public String activateGatewayVersion(Context ctx, String batteryId, String version, String energyKwh) {
        return gatewayService.activateGatewayVersion(ctx, batteryId, version, energyKwh);
    }

    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public String reportAvailableEnergy(Context ctx, String batteryId, String energyKwh) {
        return gatewayService.reportAvailableEnergy(ctx, batteryId, energyKwh);
    }

    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public String suspendDispatch(Context ctx, String batteryId) {
        return gatewayService.suspendDispatch(ctx, batteryId);
    }

    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public String authorizeExecution(Context ctx, String commandId, String attemptId) {
        return executionService.authorize(ctx, commandId, attemptId);
    }

    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public String reportExecutionOutcome(Context ctx, String commandId, String attemptId, String outcome,
                                         String deliveredEnergyKwh) {
        return executionService.reportOutcome(ctx, commandId, attemptId, outcome, deliveredEnergyKwh);
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String readBess(Context ctx, String id) {
        return JsonMapper.toJson(bessService.read(ctx, id));
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String getAllBess(Context ctx) {
        return JsonMapper.toJson(bessService.readAll(ctx));
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String readBattery(Context ctx, String id) {
        return JsonMapper.toJson(batteryService.read(ctx, id));
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String getAllBatteries(Context ctx) {
        return JsonMapper.toJson(batteryService.readAll(ctx));
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String readBatteryCoordinationState(Context ctx, String batteryId) {
        return JsonMapper.toJson(DomainSupport.state(ctx, batteryId));
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String readOperatingCertificate(Context ctx, String batteryId, String version) {
        return JsonMapper.toJson(certificateService.read(ctx, DomainSupport.certificateId(batteryId, Integer.parseInt(version))));
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String getAllOperatingCertificates(Context ctx) {
        return JsonMapper.toJson(certificateService.readAll(ctx));
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String readDispatch(Context ctx, String commandId) {
        return JsonMapper.toJson(dispatchService.read(ctx, commandId));
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String getAllDispatchCommands(Context ctx) {
        return JsonMapper.toJson(dispatchService.readAll(ctx));
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String readExecutionAttempt(Context ctx, String commandId, String attemptId) {
        return JsonMapper.toJson(executionService.read(ctx, DomainSupport.attemptId(commandId, attemptId)));
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String getAllExecutionAttempts(Context ctx) {
        return JsonMapper.toJson(executionService.readAll(ctx));
    }
}
