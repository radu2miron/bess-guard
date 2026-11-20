package edu.tucn.bessguard.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 * Preserves the original async message pattern, now supporting Fabric methods with multiple arguments.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransactionRequest {
    private TxType txType;
    private String function;
    private String[] args;
    private long receivedAt;
    private String id;
    private String initiatedBy;
}
