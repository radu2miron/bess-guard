package edu.tucn.bessguard.contract;

import edu.tucn.bessguard.model.*;
import edu.tucn.bessguard.util.JsonMapper;
import org.hyperledger.fabric.contract.Context;

import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;

/**
 * Shared deterministic validation and world-state helpers (not Fabric entry points).
 */
final class DomainSupport {
    private DomainSupport() {
    }

    static String id(String value, String field) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0 || value.indexOf(':') >= 0
                || value.indexOf('@') >= 0 || value.length() > 160) {
            throw new IllegalArgumentException(field + " must be nonblank (max 160 chars) and contain no ':', '@' or NUL.");
        }

        return value;
    }

    static String optionalText(String value, String field) {
        if (value != null && value.length() > 1024) {
            throw new IllegalArgumentException(field + " is too long.");
        }

        return value;
    }

    static Instant instant(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be ISO-8601 UTC.");
        }

        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " must be ISO-8601 UTC, e.g. 2027-01-01T00:00:00Z.", e);
        }
    }

    static Instant now(Context ctx) {
        return ctx.getStub().getTxTimestamp();
    }

    static String nowText(Context ctx) {
        return now(ctx).toString();
    }

    static double finiteNonnegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0)
            throw new IllegalArgumentException(name + " must be finite and nonnegative.");
        return value;
    }

    static double finitePositive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0)
            throw new IllegalArgumentException(name + " must be finite and positive.");
        return value;
    }

    // The backend enforces application roles (ADMIN, CERTIFIER, EMS, GATEWAY) using Spring Security.
    // The chaincode allows only the backend's Fabric service identity to submit state changes.
    // These values match the default test-network identity; update them for a different backend certificate.
    private static final String BACKEND_MSP_ID = "Org1MSP";
    private static final String BACKEND_CERTIFICATE_CN = "User1@org1.example.com";

    static void requireTrustedBackend(Context ctx) {
        if (ctx == null || ctx.getClientIdentity() == null) {
            throw new SecurityException("Missing Fabric client identity.");
        }

        var identity = ctx.getClientIdentity();
        if (!BACKEND_MSP_ID.equals(identity.getMSPID())) {
            throw new SecurityException("Fabric client belongs to an unauthorized MSP.");
        }

        X509Certificate certificate = identity.getX509Certificate();
        if (certificate == null) {
            throw new SecurityException("A trusted X.509 enrollment certificate is required.");
        }

        String commonName = null;
        try {
            LdapName subject = new LdapName(certificate.getSubjectX500Principal().getName());
            for (Rdn rdn : subject.getRdns()) {
                if ("CN".equalsIgnoreCase(rdn.getType())) {
                    commonName = String.valueOf(rdn.getValue());
                    break;
                }
            }
        } catch (InvalidNameException ex) {
            throw new SecurityException("Invalid X.509 subject in Fabric identity.", ex);
        }

        if (!BACKEND_CERTIFICATE_CN.equals(commonName)) {
            throw new SecurityException("Unauthorized Fabric service identity.");
        }
    }

    static String caller(Context ctx) {
        return ctx.getClientIdentity().getMSPID() + ":" + ctx.getClientIdentity().getId();
    }

    static String key(DocType type, String id) {
        return type.name() + ":" + id;
    }

    static String certificateId(String batteryId, int version) {
        return id(batteryId, "batteryId") + "@" + version;
    }

    static String attemptId(String commandId, String attemptId) {
        return id(commandId, "commandId") + "@" + id(attemptId, "attemptId");
    }

    static <T> T read(Context ctx, DocType type, String id, Class<T> klass) {
        String value = ctx.getStub().getStringState(key(type, id));
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(type + " '" + id + "' does not exist.");
        return JsonMapper.fromJson(value, klass);
    }

    static boolean exists(Context ctx, DocType type, String id) {
        String value = ctx.getStub().getStringState(key(type, id));
        return value != null && !value.isBlank();
    }

    static void create(Context ctx, DocType type, String id, Object value) {
        String key = key(type, id);
        String previous = ctx.getStub().getStringState(key);
        if (previous != null && !previous.isEmpty()) {
            throw new IllegalArgumentException(type + " '" + id + "' already exists.");
        }
        ctx.getStub().putStringState(key, JsonMapper.toJson(value));
    }

    static void put(Context ctx, DocType type, String id, Object value) {
        ctx.getStub().putStringState(key(type, id), JsonMapper.toJson(value));
    }

    static BatteryCoordinationState state(Context ctx, String batteryId) {
        return read(ctx, DocType.BATTERY_COORDINATION_STATE, id(batteryId, "batteryId"), BatteryCoordinationState.class);
    }

    static void saveState(Context ctx, BatteryCoordinationState state) {
        state.setRevision(state.getRevision() + 1);
        state.setLastUpdatedAt(nowText(ctx));
        // This common key is read and written by every state-changing operation: Fabric MVCC guard.
        put(ctx, DocType.BATTERY_COORDINATION_STATE, state.getId(), state);
    }

    static OperatingCertificate certificate(Context ctx, String batteryId, int version) {
        return read(ctx, DocType.OPERATING_CERTIFICATE, certificateId(batteryId, version), OperatingCertificate.class);
    }

    static void validateCertificate(OperatingCertificate cert, String batteryId, int expectedVersion) {
        if (cert == null || cert.getDocType() != DocType.OPERATING_CERTIFICATE)
            throw new IllegalArgumentException("Invalid certificate docType.");
        id(cert.getBatteryId(), "certificate.batteryId");
        if (!cert.getBatteryId().equals(batteryId) || cert.getVersion() != expectedVersion || expectedVersion < 1)
            throw new IllegalArgumentException("Certificate battery/version mismatch.");
        String expectedId = certificateId(batteryId, expectedVersion);
        if (cert.getId() != null && !expectedId.equals(cert.getId()))
            throw new IllegalArgumentException("Certificate ID must be " + expectedId);
        finitePositive(cert.getMaxDischargePowerKw(), "maxDischargePowerKw");
        finiteNonnegative(cert.getMinEnergyKwh(), "minEnergyKwh");
        finitePositive(cert.getMaxEnergyKwh(), "maxEnergyKwh");
        if (cert.getMinEnergyKwh() >= cert.getMaxEnergyKwh())
            throw new IllegalArgumentException("Energy bounds must be increasing.");
        if (!instant(cert.getValidFrom(), "validFrom").isBefore(instant(cert.getValidUntil(), "validUntil")))
            throw new IllegalArgumentException("Certificate validity window is empty.");
        optionalText(cert.getEvidenceRef(), "evidenceRef");
    }

    static void verifyValidNow(OperatingCertificate cert, Instant t) {
        if (t.isBefore(instant(cert.getValidFrom(), "validFrom")) || !t.isBefore(instant(cert.getValidUntil(), "validUntil")))
            throw new IllegalArgumentException("Certificate is not valid at the transaction timestamp.");
    }

    static double hours(String start, String end) {
        Instant a = instant(start, "startTime");
        Instant b = instant(end, "endTime");
        if (!a.isBefore(b)) throw new IllegalArgumentException("Dispatch interval must be positive.");
        Duration duration = Duration.between(a, b);
        return (duration.getSeconds() + duration.getNano() / 1_000_000_000.0) / 3600.0;
    }

    static double requiredEnergy(DispatchCommand d) {
        return finitePositive(d.getRequestedPowerKw(), "requestedPowerKw") * hours(d.getStartTime(), d.getEndTime());
    }

    static boolean overlaps(DispatchCommand a, DispatchCommand b) {
        return instant(a.getStartTime(), "startTime").isBefore(instant(b.getEndTime(), "endTime"))
                && instant(b.getStartTime(), "startTime").isBefore(instant(a.getEndTime(), "endTime"));
    }

    static boolean reserving(DispatchCommand command) {
        return command.getStatus() == DispatchStatus.RESERVED || command.getStatus() == DispatchStatus.DISPATCHED;
    }
}
