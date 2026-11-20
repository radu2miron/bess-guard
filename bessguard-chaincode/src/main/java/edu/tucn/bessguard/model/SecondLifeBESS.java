package edu.tucn.bessguard.model;

import lombok.Data;

/**
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
