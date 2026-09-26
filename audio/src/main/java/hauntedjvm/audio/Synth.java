package hauntedjvm.audio;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Procedural sound: every sample is computed, nothing is recorded or bundled.
 *
 * <p>The bed is layered from mains hum, brown-noise room tone and a pair of detuned low sines
 * whose beating speeds up with tension. Static, a high whine and one-shot cues sit on top. All
 * parameters are smoothed per sample to avoid zipper noise, and the mix goes through a soft
 * clipper so nothing ever hard-clips no matter how many cues overlap.
 *
 * <p>Not thread-safe: owned by the audio thread. Deterministic for a given noise seed, which is
 * what makes it testable without a sound card.
 */
public final class Synth {

    private static final double TWO_PI = Math.PI * 2;
    private static final double SMOOTHING_SECONDS = 0.35;

    private final double sampleRate;
    private final double smoothing;
    private long noiseState;

    private double hum;
    private double droneA;
    private double droneB;
    private double whine;
    private double brown;
    private double staticHp;
    private double lastWhite;
    private double roomLp;

    private double tension;
    private double level;
    private double signal;
    private double darkness;
    private double presence;

    private final List<Voice> voices = new ArrayList<>();

    private final class Voice {
        final Cue cue;
        final int length;
        int position;
        double phase;

        Voice(Cue cue) {
            this.cue = cue;
            this.length = (int) (cue.seconds() * sampleRate);
        }

        boolean done() {
            return position >= length;
        }

        double next() {
            double t = position / sampleRate;
            double progress = (double) position / length;
            position++;
            return switch (cue) {
                case BLIP -> tone(880 * (1 - 0.1 * progress), t) * Math.exp(-t * 28) * 0.35;
                case ESCALATE -> {
                    double f = 220 * Math.pow(0.5, progress);
                    double tremolo = 0.6 + 0.4 * Math.sin(TWO_PI * 7 * t);
                    yield tone(f, t) * tremolo * envelope(progress, 0.05, 0.4) * 0.4;
                }
                case DOOR -> (Math.sin(TWO_PI * 62 * t) * Math.exp(-t * 14) + white() * Math.exp(-t * 90) * 0.5) * 0.5;
                case RADIO -> {
                    double squelch = progress > 0.8 ? tone(1000, t) * 0.25 : 0;
                    yield (bandNoise() * 0.6 + squelch) * envelope(progress, 0.02, 0.15) * 0.45;
                }
                case SWITCH -> white() * Math.exp(-t * 900) * 0.6;
                case STATIC_BURST -> white() * envelope(progress, 0.01, 0.3) * 0.3;
                case REWIND -> {
                    double f = 200 + 1800 * progress * progress;
                    yield (tone(f, t) * 0.25 + white() * 0.12) * envelope(progress, 0.1, 0.2);
                }
            };
        }

        private double tone(double freq, double t) {
            phase += TWO_PI * freq / sampleRate;
            if (phase > TWO_PI) {
                phase -= TWO_PI;
            }
            return Math.sin(phase);
        }
    }

    public Synth(double sampleRate, long noiseSeed) {
        if (sampleRate < 8_000) {
            throw new IllegalArgumentException("sample rate too low: " + sampleRate);
        }
        this.sampleRate = sampleRate;
        this.smoothing = 1 - Math.exp(-1 / (SMOOTHING_SECONDS * sampleRate));
        this.noiseState = noiseSeed == 0 ? 0x9E3779B97F4A7C15L : noiseSeed;
    }

    public void trigger(Cue cue) {
        // Bound the polyphony: a storm of events must not become a wall of noise.
        if (voices.size() < 12) {
            voices.add(new Voice(cue));
        }
    }

    /** Fills {@code out} with samples in {@code [-1, 1]}, moving toward {@code mood}. */
    public void render(float[] out, int frames, AudioMood mood) {
        for (int i = 0; i < frames; i++) {
            tension += (mood.tension() - tension) * smoothing;
            level += (mood.level() - level) * smoothing;
            signal += (mood.signal() - signal) * smoothing;
            darkness += (mood.darkness() - darkness) * smoothing;
            presence += (mood.presence() - presence) * smoothing;
            out[i] = (float) Math.tanh(bed() + cues());
        }
    }

    private double bed() {
        hum = advance(hum, 50);
        double mains = Math.sin(hum) * 0.05 + Math.sin(hum * 2) * 0.02 * (0.4 + level) + Math.sin(hum * 3) * 0.015 * level;

        double w = white();
        brown = (brown + w * 0.02) * 0.998;
        roomLp += (brown - roomLp) * (0.02 + 0.2 * (1 - darkness));
        double room = roomLp * 0.1;

        droneA = advance(droneA, 55);
        droneB = advance(droneB, 55.3 + tension * 1.4);
        double drone = (Math.sin(droneA) + Math.sin(droneB)) * 0.05 * tension;
        if (level > 0.7) {
            drone += Math.sin(droneA * 0.5) * 0.04 * (level - 0.7) / 0.3;
        }

        staticHp = 0.7 * (staticHp + w - lastWhite);
        lastWhite = w;
        double crackle = nextUnit() < 0.0008 * signal ? (nextUnit() * 2 - 1) * 0.8 : 0;
        double stat = (staticHp * 0.18 + crackle) * signal;

        whine = advance(whine, 3150 + 40 * Math.sin(hum * 0.01));
        double tinnitus = Math.sin(whine) * 0.012 * presence;

        return mains + room + drone + stat + tinnitus;
    }

    private double cues() {
        double sum = 0;
        Iterator<Voice> it = voices.iterator();
        while (it.hasNext()) {
            Voice v = it.next();
            sum += v.next();
            if (v.done()) {
                it.remove();
            }
        }
        return sum;
    }

    private double advance(double phase, double freq) {
        phase += TWO_PI * freq / sampleRate;
        return phase > TWO_PI * 1000 ? phase - TWO_PI * 1000 : phase;
    }

    private static double envelope(double progress, double attack, double release) {
        if (progress < attack) {
            return progress / attack;
        }
        if (progress > 1 - release) {
            return Math.max(0, (1 - progress) / release);
        }
        return 1;
    }

    private double bandNoise() {
        return (white() + white() + white()) / 3;
    }

    private double white() {
        return nextUnit() * 2 - 1;
    }

    /** Xorshift64*; the audio has its own noise source and never touches simulation randomness. */
    private double nextUnit() {
        long x = noiseState;
        x ^= x >>> 12;
        x ^= x << 25;
        x ^= x >>> 27;
        noiseState = x;
        return ((x * 0x2545F4914F6CDD1DL) >>> 11) * 0x1.0p-53;
    }
}
