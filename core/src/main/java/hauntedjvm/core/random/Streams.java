package hauntedjvm.core.random;

/**
 * Purpose keys for {@link Rng#stream}. Each constant names one family of decisions.
 *
 * <p>Values are arbitrary but must never change once released: they are part of the
 * reproducibility contract of every saved session.
 */
public final class Streams {

    public static final long GENESIS = 0x01;
    public static final long BEHAVIOR = 0x10;
    public static final long GOAL = 0x11;
    public static final long PERCEPTION = 0x20;
    public static final long SOCIAL = 0x30;
    public static final long DISTORTION = 0x31;
    public static final long MEMORY = 0x40;
    public static final long PROCESS = 0x50;
    public static final long DEVICE = 0x60;
    public static final long ANOMALY = 0x70;
    public static final long ANOMALY_TARGET = 0x71;
    public static final long RARE = 0x72;
    public static final long MESSAGE = 0x73;
    public static final long UNKNOWN = 0x80;
    public static final long DIRECTIVE = 0x90;

    private Streams() {
    }
}
