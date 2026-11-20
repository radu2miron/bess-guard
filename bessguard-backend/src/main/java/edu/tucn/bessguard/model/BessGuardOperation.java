package edu.tucn.bessguard.model;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 * Fabric facade functions and required backend roles.
 */
public enum BessGuardOperation {
    REGISTER_BESS("registerBess", "ADMIN", TxType.SUBMIT),
    REGISTER_BATTERY("registerBattery", "ADMIN", TxType.SUBMIT),
    ASSIGN_BATTERY("assignBatteryToBess", "ADMIN", TxType.SUBMIT),
    ISSUE_INITIAL_CERTIFICATE("issueInitialCertificate", "CERTIFIER", TxType.SUBMIT),
    BEGIN_RECERTIFICATION("beginRecertification", "CERTIFIER", TxType.SUBMIT),
    ACKNOWLEDGE_QUIESCENCE("acknowledgeQuiescence", "GATEWAY", TxType.SUBMIT),
    RETRY_QUIESCENCE("retryQuiescence", "CERTIFIER", TxType.SUBMIT),
    ACTIVATE_CERTIFICATE("activateCertificate", "CERTIFIER", TxType.SUBMIT),
    ACTIVATE_GATEWAY_VERSION("activateGatewayVersion", "GATEWAY", TxType.SUBMIT),
    REPORT_AVAILABLE_ENERGY("reportAvailableEnergy", "GATEWAY", TxType.SUBMIT),
    SUSPEND_DISPATCH("suspendDispatch", "GATEWAY", TxType.SUBMIT),
    RESERVE_DISPATCH("reserveDispatch", "EMS", TxType.SUBMIT),
    CANCEL_DISPATCH("cancelDispatch", "EMS", TxType.SUBMIT),
    AUTHORIZE_EXECUTION("authorizeExecution", "GATEWAY", TxType.SUBMIT),
    REPORT_EXECUTION_OUTCOME("reportExecutionOutcome", "GATEWAY", TxType.SUBMIT),
    READ_BESS("readBess", null, TxType.READ),
    GET_ALL_BESS("getAllBess", null, TxType.READ),
    READ_BATTERY("readBattery", null, TxType.READ),
    GET_ALL_BATTERIES("getAllBatteries", null, TxType.READ),
    READ_COORDINATION_STATE("readBatteryCoordinationState", null, TxType.READ),
    READ_CERTIFICATE("readOperatingCertificate", null, TxType.READ),
    GET_ALL_CERTIFICATES("getAllOperatingCertificates", null, TxType.READ),
    READ_DISPATCH("readDispatch", null, TxType.READ),
    GET_ALL_DISPATCHES("getAllDispatchCommands", null, TxType.READ),
    READ_EXECUTION_ATTEMPT("readExecutionAttempt", null, TxType.READ),
    GET_ALL_EXECUTION_ATTEMPTS("getAllExecutionAttempts", null, TxType.READ);

    private final String function;
    private final String requiredRole;
    private final TxType txType;

    BessGuardOperation(String function, String requiredRole, TxType txType) {
        this.function = function;
        this.requiredRole = requiredRole;
        this.txType = txType;
    }

    public String function() {
        return function;
    }

    public String requiredRole() {
        return requiredRole;
    }

    public TxType txType() {
        return txType;
    }

}
