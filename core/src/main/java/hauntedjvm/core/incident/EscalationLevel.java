package hauntedjvm.core.incident;

public enum EscalationLevel {
    NORMAL,
    ODD,
    UNSTABLE,
    CORRUPTED,
    AWARE,
    CRITICAL;

    public static EscalationLevel of(int level) {
        return values()[Math.clamp(level, 0, values().length - 1)];
    }

    public int number() {
        return ordinal();
    }

    public String label() {
        return "LEVEL " + ordinal() + " — " + name();
    }
}
