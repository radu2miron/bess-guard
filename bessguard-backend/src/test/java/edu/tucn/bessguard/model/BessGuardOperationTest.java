package edu.tucn.bessguard.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
class BessGuardOperationTest {

    @Test
    void dispatchAndCertificateOperationsHaveDifferentRoles() {
        assertEquals("EMS", BessGuardOperation.RESERVE_DISPATCH.requiredRole());
        assertEquals("CERTIFIER", BessGuardOperation.BEGIN_RECERTIFICATION.requiredRole());
        assertEquals("GATEWAY", BessGuardOperation.AUTHORIZE_EXECUTION.requiredRole());
        assertEquals(TxType.READ, BessGuardOperation.READ_DISPATCH.txType());
    }
}
