package edu.tucn.bessguard.model;

import lombok.Data;

/**
 * BESSGuard domain model.
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
@Data
public class BatteryUnit {
    private DocType docType = DocType.BATTERY_UNIT;
    private String id;
    private String bessId;
    private String serialNumber;
    private double nominalCapacityKwh;
    private String createdAt;
}
