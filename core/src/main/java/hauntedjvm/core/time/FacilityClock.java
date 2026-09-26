package hauntedjvm.core.time;

/**
 * Maps simulation ticks to the facility's wall clock.
 *
 * <p>One tick is one second of facility time. The recording starts at 02:40:00, a little into
 * the night shift, so the first minutes of every session look like ordinary routine.
 */
public final class FacilityClock {

    public static final long START_SECOND_OF_DAY = 2 * 3600 + 40 * 60;
    private static final long SECONDS_PER_DAY = 86_400;

    private FacilityClock() {
    }

    /** Formats a tick as {@code HH:mm:ss}. Negative ticks (backdated records) wrap to the previous day. */
    public static String format(long tick) {
        long s = Math.floorMod(START_SECOND_OF_DAY + tick, SECONDS_PER_DAY);
        return String.format("%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    /** Formats an elapsed duration in ticks as {@code HH:mm:ss}. */
    public static String duration(long ticks) {
        long s = Math.max(0, ticks);
        return String.format("%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }
}
