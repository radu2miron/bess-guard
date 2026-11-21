package edu.tucn.bessguard.model;

import lombok.Data;

/**
 * BESSGuard domain model.
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
@Data
public class SecondLifeBESS {
    private DocType docType = DocType.SECOND_LIFE_BESS;
    private String id;
    private String name;
    private String description;
    private String createdAt;
}
