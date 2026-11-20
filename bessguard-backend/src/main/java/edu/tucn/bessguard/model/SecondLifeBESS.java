package edu.tucn.bessguard.model;

import lombok.Data;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 * BESSGuard domain model.
 */
@Data
public class SecondLifeBESS {
    private DocType docType = DocType.SECOND_LIFE_BESS;
    private String id;
    private String name;
    private String description;
    private String createdAt;
}
