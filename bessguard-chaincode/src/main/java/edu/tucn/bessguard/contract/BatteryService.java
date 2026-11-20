package edu.tucn.bessguard.contract;

import edu.tucn.bessguard.model.*;
import edu.tucn.bessguard.util.JsonMapper;
import org.hyperledger.fabric.contract.Context;

/**
 * Manages battery identities and their per-battery coordination guard record.
 */
public class BatteryService extends AbstractLedgerService<BatteryUnit> {
    public BatteryService() {
        super(BatteryUnit.class, DocType.BATTERY_UNIT, BatteryUnit::getId, BatteryUnit::getDocType);
    }

    public String register(Context ctx, String json) {
        DomainSupport.requireTrustedBackend(ctx);
        BatteryUnit battery = JsonMapper.fromJson(json, BatteryUnit.class);

        if (battery == null || battery.getDocType() != DocType.BATTERY_UNIT) {
            throw new IllegalArgumentException("Invalid battery docType.");
        }

        DomainSupport.id(battery.getId(), "id");
        DomainSupport.id(battery.getBessId(), "bessId");
        DomainSupport.id(battery.getSerialNumber(), "serialNumber");
        DomainSupport.finitePositive(battery.getNominalCapacityKwh(), "nominalCapacityKwh");
        DomainSupport.read(ctx, DocType.SECOND_LIFE_BESS, battery.getBessId(), SecondLifeBESS.class);
        battery.setCreatedAt(DomainSupport.nowText(ctx));
        super.create(ctx, JsonMapper.toJson(battery));
        BatteryCoordinationState state = new BatteryCoordinationState();
        state.setId(battery.getId());
        state.setActiveCertificateVersion(0);
        state.setMode(CoordinationMode.SUSPENDED);
        state.setAvailableEnergyKwh(0); // gateway must report actual estimated energy
        state.setLastUpdatedAt(DomainSupport.nowText(ctx));
        DomainSupport.create(ctx, DocType.BATTERY_COORDINATION_STATE, state.getId(), state);

        return JsonMapper.toJson(battery);
    }

    public String assign(Context ctx, String batteryId, String bessId) {
        DomainSupport.requireTrustedBackend(ctx);
        BatteryUnit unit = read(ctx, DomainSupport.id(batteryId, "batteryId"));
        DomainSupport.read(ctx, DocType.SECOND_LIFE_BESS, DomainSupport.id(bessId, "bessId"), SecondLifeBESS.class);
        BatteryCoordinationState state = DomainSupport.state(ctx, unit.getId());

        if (state.getMode() != CoordinationMode.SUSPENDED || !state.getOpenReservationIds().isEmpty()) {
            throw new IllegalArgumentException("Battery can be reassigned only while suspended with no open reservations.");
        }

        unit.setBessId(bessId);
        DomainSupport.put(ctx, DocType.BATTERY_UNIT, batteryId, unit);
        DomainSupport.saveState(ctx, state);

        return JsonMapper.toJson(unit);
    }
}
