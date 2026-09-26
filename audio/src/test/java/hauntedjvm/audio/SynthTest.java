package hauntedjvm.audio;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SynthTest {

    private static final int RATE = 22_050;

    private static float[] render(Synth synth, AudioMood mood, double seconds) {
        int n = (int) (RATE * seconds);
        float[] out = new float[n];
        synth.render(out, n, mood);
        return out;
    }

    private static double rms(float[] s, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) {
            sum += s[i] * s[i];
        }
        return Math.sqrt(sum / (to - from));
    }

    /** Mean absolute first difference: a cheap proxy for high-frequency content. */
    private static double roughness(float[] s) {
        double sum = 0;
        for (int i = 1; i < s.length; i++) {
            sum += Math.abs(s[i] - s[i - 1]);
        }
        return sum / (s.length - 1);
    }

    @Test
    void theBedIsAudibleButNeverClips() {
        float[] out = render(new Synth(RATE, 1), new AudioMood(1, 1, 1, 1, 1), 3);
        for (float v : out) {
            assertThat(Float.isFinite(v)).isTrue();
            assertThat(Math.abs(v)).isLessThan(1f);
        }
        assertThat(rms(out, RATE, out.length)).isBetween(0.01, 0.7);
    }

    @Test
    void interferenceAddsHighFrequencyContent() {
        float[] clean = render(new Synth(RATE, 2), new AudioMood(0.2, 0, 0, 0, 0), 2);
        float[] noisy = render(new Synth(RATE, 2), new AudioMood(0.2, 0, 1, 0, 0), 2);
        assertThat(roughness(noisy)).isGreaterThan(roughness(clean) * 2);
    }

    @Test
    void cuesAreTransient() {
        Synth synth = new Synth(RATE, 3);
        render(synth, AudioMood.QUIET, 1);
        float[] before = render(synth, AudioMood.QUIET, 0.2);
        synth.trigger(Cue.BLIP);
        float[] during = render(synth, AudioMood.QUIET, 0.1);
        render(synth, AudioMood.QUIET, 0.5);
        float[] after = render(synth, AudioMood.QUIET, 0.2);
        assertThat(rms(during, 0, during.length)).isGreaterThan(rms(before, 0, before.length) * 1.5);
        assertThat(rms(after, 0, after.length)).isLessThan(rms(during, 0, during.length));
    }

    @Test
    void overlappingCuesStayBounded() {
        Synth synth = new Synth(RATE, 4);
        for (int i = 0; i < 100; i++) {
            for (Cue c : Cue.values()) {
                synth.trigger(c);
            }
        }
        for (float v : render(synth, new AudioMood(1, 1, 1, 1, 1), 2)) {
            assertThat(Math.abs(v)).isLessThan(1f);
        }
    }

    @Test
    void sameNoiseSeedSameSound() {
        float[] a = render(new Synth(RATE, 5), new AudioMood(0.5, 0.5, 0.5, 0.5, 0.5), 0.5);
        float[] b = render(new Synth(RATE, 5), new AudioMood(0.5, 0.5, 0.5, 0.5, 0.5), 0.5);
        assertThat(a).containsExactly(b);
    }

    @Test
    void aSilentEngineAcceptsCallsHarmlessly() {
        try (AudioEngine engine = AudioEngine.silent()) {
            engine.setMood(new AudioMood(1, 1, 1, 1, 1));
            engine.trigger(Cue.ESCALATE);
            assertThat(engine.available()).isFalse();
        }
    }
}
