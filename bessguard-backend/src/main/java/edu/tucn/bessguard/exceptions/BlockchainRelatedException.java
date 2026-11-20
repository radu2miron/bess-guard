package edu.tucn.bessguard.exceptions;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
public class BlockchainRelatedException extends RuntimeException {

    public BlockchainRelatedException(String message) {
        super(message);
    }

    public BlockchainRelatedException(String message, Throwable cause) {
        super(message, cause);
    }
}
