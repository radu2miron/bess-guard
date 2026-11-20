package edu.tucn.bessguard.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 * Software-only BMS and battery model. No physical inverter or battery is controlled.
 * The gateway holds each battery's execution lock while accessing this simulator.
 */
@Service
public class SimulatedBmsService {

    private final ConcurrentMap<String, BatteryLimits> batteries = new ConcurrentHashMap<>();

    public void install(String batteryId, double maxPowerKw, double minEnergyKwh, double availableEnergyKwh) {
        batteries.put(batteryId, new BatteryLimits(maxPowerKw, minEnergyKwh, availableEnergyKwh));
    }

    public void updateEnergy(String batteryId, double availableEnergyKwh) {
        BatteryLimits limits = requireBattery(batteryId);
        limits.availableEnergyKwh = availableEnergyKwh;
    }

    public boolean canDischarge(String batteryId, double requestedPowerKw, double energyKwh) {
        BatteryLimits limits = requireBattery(batteryId);
        return requestedPowerKw > 0 && requestedPowerKw <= limits.maxPowerKw
                && energyKwh >= 0 && limits.availableEnergyKwh - energyKwh >= limits.minEnergyKwh;
    }

    public void discharge(String batteryId, double energyKwh) {
        BatteryLimits limits = requireBattery(batteryId);

        if (energyKwh < 0 || limits.availableEnergyKwh - energyKwh < limits.minEnergyKwh) {
            throw new IllegalStateException("Simulated BMS energy limit exceeded.");
        }

        limits.availableEnergyKwh -= energyKwh;
    }

    public Map<String, Object> state(String batteryId) {
        BatteryLimits limits = batteries.get(batteryId);

        if (limits == null) {
            return Map.of("initialized", false);
        }

        return Map.of("initialized", true, "availableEnergyKwh", limits.availableEnergyKwh,
                "minEnergyKwh", limits.minEnergyKwh, "maxPowerKw", limits.maxPowerKw);
    }

    private BatteryLimits requireBattery(String batteryId) {
        BatteryLimits limits = batteries.get(batteryId);

        if (limits == null) {
            throw new IllegalStateException("Simulated BMS not initialized for battery " + batteryId);
        }

        return limits;
    }

    private static final class BatteryLimits {
        private final double maxPowerKw;
        private final double minEnergyKwh;
        private double availableEnergyKwh;

        private BatteryLimits(double maxPowerKw, double minEnergyKwh, double availableEnergyKwh) {
            this.maxPowerKw = maxPowerKw;
            this.minEnergyKwh = minEnergyKwh;
            this.availableEnergyKwh = availableEnergyKwh;
        }
    }
}
