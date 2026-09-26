package hauntedjvm.core.entity;

public enum CameraStatus {
    ONLINE,
    /** Picture degraded by static. */
    INTERFERENCE,
    /** The feed shows the room as it was some ticks ago. */
    LOOPING,
    OFFLINE
}
