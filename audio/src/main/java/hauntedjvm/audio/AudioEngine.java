package hauntedjvm.audio;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Streams the synth to the default output device.
 *
 * <p>Runs on a dedicated platform thread. {@link SourceDataLine#write} blocks in native code
 * until the device has room, which would pin the carrier of a virtual thread, and audio wants
 * a thread the scheduler treats predictably. Everything else talks to it through a volatile
 * mood and a small bounded cue queue; when the queue is full, cues are dropped rather than
 * delaying the caller.
 *
 * <p>If no output line is available (headless machines, CI) the engine becomes a silent no-op.
 * Audio is output-only: the application never opens an input line.
 */
public final class AudioEngine implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AudioEngine.class);
    private static final float SAMPLE_RATE = 44_100f;
    private static final int FRAMES_PER_WRITE = 1_024;

    private final BlockingQueue<Cue> cues = new ArrayBlockingQueue<>(32);
    private final Thread thread;
    private volatile AudioMood mood = AudioMood.QUIET;
    private volatile double volume = 0.8;
    private volatile boolean muted;
    private volatile boolean running = true;

    private AudioEngine(SourceDataLine line) {
        this.thread = line == null ? null : Thread.ofPlatform().name("audio-synth").daemon().priority(Thread.MAX_PRIORITY)
                .start(() -> loop(line));
    }

    /** Opens the default output, or returns a silent engine if there is none. */
    public static AudioEngine open(boolean startMuted) {
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        try {
            SourceDataLine line = AudioSystem.getSourceDataLine(format);
            line.open(format, FRAMES_PER_WRITE * 2 * 4);
            line.start();
            AudioEngine engine = new AudioEngine(line);
            engine.muted = startMuted;
            LOG.atInfo().addKeyValue("sampleRate", SAMPLE_RATE).log("audio output opened");
            return engine;
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            LOG.atWarn().addKeyValue("reason", e.toString()).log("no audio output; continuing silently");
            return new AudioEngine(null);
        }
    }

    public static AudioEngine silent() {
        return new AudioEngine(null);
    }

    public boolean available() {
        return thread != null;
    }

    public void setMood(AudioMood next) {
        mood = next;
    }

    public void trigger(Cue cue) {
        if (thread != null && !muted) {
            cues.offer(cue);
        }
    }

    public void setMuted(boolean value) {
        muted = value;
    }

    public boolean muted() {
        return muted;
    }

    public void setVolume(double value) {
        volume = Math.clamp(value, 0.0, 1.0);
    }

    private void loop(SourceDataLine line) {
        Synth synth = new Synth(SAMPLE_RATE, System.nanoTime());
        float[] samples = new float[FRAMES_PER_WRITE];
        byte[] bytes = new byte[FRAMES_PER_WRITE * 2];
        try {
            while (running) {
                Cue cue;
                while ((cue = cues.poll()) != null) {
                    synth.trigger(cue);
                }
                synth.render(samples, FRAMES_PER_WRITE, mood);
                double gain = muted ? 0 : volume;
                for (int i = 0; i < FRAMES_PER_WRITE; i++) {
                    int s = (int) Math.round(samples[i] * gain * 32_000);
                    bytes[2 * i] = (byte) s;
                    bytes[2 * i + 1] = (byte) (s >> 8);
                }
                line.write(bytes, 0, bytes.length);
            }
        } catch (RuntimeException e) {
            LOG.atWarn().setCause(e).log("audio thread stopped");
        } finally {
            line.stop();
            line.close();
        }
    }

    @Override
    public void close() {
        running = false;
        if (thread != null) {
            try {
                thread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
