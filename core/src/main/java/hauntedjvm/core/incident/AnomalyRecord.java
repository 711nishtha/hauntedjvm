package hauntedjvm.core.incident;

/** A detected anomaly and what has happened to it since. */
public record AnomalyRecord(DetectedAnomaly anomaly, Status status, long statusTick) {

    public enum Status {
        ACTIVE,
        /** Aged out or no longer holds. */
        RESOLVED,
        /** Withdrawn from the record. Some anomalies do not like being looked at. */
        RETRACTED
    }

    public boolean active() {
        return status == Status.ACTIVE;
    }

    public AnomalyRecord withStatus(Status newStatus, long tick) {
        return new AnomalyRecord(anomaly, newStatus, tick);
    }
}
