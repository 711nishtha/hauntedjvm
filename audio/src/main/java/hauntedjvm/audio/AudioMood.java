package hauntedjvm.audio;

/**
 * What the room should sound like right now. Every field is in {@code [0, 1]}; the synth glides
 * toward new values over a fraction of a second, so the caller can update it every frame.
 *
 * @param tension  overall unease; drives the beating low drone
 * @param level    escalation level 0..5 scaled to [0, 1]; adds harmonics to the mains hum
 * @param signal   interference on the watched feed; mixes in static
 * @param darkness how dark the watched room is; muffles the room tone
 * @param presence something is on screen that should not be; a faint high whine
 */
public record AudioMood(double tension, double level, double signal, double darkness, double presence) {

    public static final AudioMood QUIET = new AudioMood(0, 0, 0, 0, 0);

    public AudioMood {
        tension = clamp(tension);
        level = clamp(level);
        signal = clamp(signal);
        darkness = clamp(darkness);
        presence = clamp(presence);
    }

    private static double clamp(double v) {
        return Double.isNaN(v) ? 0 : Math.clamp(v, 0.0, 1.0);
    }
}
