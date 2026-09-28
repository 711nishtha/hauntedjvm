package hauntedjvm.app.render;

import java.util.SplittableRandom;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.ImagePattern;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;

/**
 * Analogue video artefacts, drawn on top of a finished frame.
 *
 * <p>Canvas has no pixel shaders, so everything here is compositing: pre-generated noise
 * textures blitted at random offsets, a scanline pattern, a vignette gradient and a slowly
 * rolling hum bar. Costs a few draw calls per frame regardless of resolution.
 */
public final class PostFx {

    private static final int NOISE_SIZE = 192;
    private static final int NOISE_FRAMES = 6;

    private final Image[] noise = new Image[NOISE_FRAMES];
    private final ImagePattern scanlines;
    private final SplittableRandom random = new SplittableRandom(0x5EED);

    public PostFx() {
        SplittableRandom r = new SplittableRandom(7);
        for (int f = 0; f < NOISE_FRAMES; f++) {
            WritableImage img = new WritableImage(NOISE_SIZE, NOISE_SIZE);
            PixelWriter w = img.getPixelWriter();
            for (int y = 0; y < NOISE_SIZE; y++) {
                for (int x = 0; x < NOISE_SIZE; x++) {
                    int v = r.nextInt(256);
                    int a = r.nextInt(256);
                    w.setArgb(x, y, (a << 24) | (v << 16) | (v << 8) | v);
                }
            }
            noise[f] = img;
        }
        WritableImage line = new WritableImage(1, 3);
        line.getPixelWriter().setArgb(0, 0, 0x00000000);
        line.getPixelWriter().setArgb(0, 1, 0x00000000);
        line.getPixelWriter().setArgb(0, 2, 0xFF000000);
        scanlines = new ImagePattern(line, 0, 0, 1, 3, false);
    }

    /** Film grain; {@code amount} 0..1. */
    public void grain(GraphicsContext g, double w, double h, double amount) {
        if (amount <= 0) {
            return;
        }
        Image img = noise[random.nextInt(NOISE_FRAMES)];
        double scale = 2.0;
        double ox = -random.nextInt(NOISE_SIZE);
        double oy = -random.nextInt(NOISE_SIZE);
        g.save();
        g.setGlobalAlpha(Math.min(1, amount));
        for (double y = oy; y < h; y += NOISE_SIZE * scale) {
            for (double x = ox; x < w; x += NOISE_SIZE * scale) {
                g.drawImage(img, x, y, NOISE_SIZE * scale, NOISE_SIZE * scale);
            }
        }
        g.restore();
    }

    public void scanlines(GraphicsContext g, double w, double h, double strength) {
        g.save();
        g.setGlobalAlpha(strength);
        g.setFill(scanlines);
        g.fillRect(0, 0, w, h);
        g.restore();
    }

    public void vignette(GraphicsContext g, double w, double h, double strength) {
        g.save();
        g.setFill(new RadialGradient(0, 0, w / 2, h / 2, Math.hypot(w, h) / 2, false, CycleMethod.NO_CYCLE,
                new Stop(0.55, Color.TRANSPARENT), new Stop(1, Color.color(0, 0, 0, strength))));
        g.fillRect(0, 0, w, h);
        g.restore();
    }

    /** The slow bright bar that rolls down old monitors with a ground loop. */
    public void humBar(GraphicsContext g, double w, double h, long nanos, double strength) {
        double period = 7.0;
        double pos = ((nanos / 1e9) % period) / period;
        double y = pos * (h + 120) - 60;
        g.save();
        g.setGlobalAlpha(strength);
        g.setFill(Color.web("#dfffe0"));
        for (int i = 0; i < 12; i++) {
            g.setGlobalAlpha(strength * (1 - Math.abs(i - 6) / 6.0));
            g.fillRect(0, y + i * 5, w, 5);
        }
        g.restore();
    }

    /** Heavy interference: torn bands of static. */
    public void interference(GraphicsContext g, double w, double h, double amount) {
        if (amount <= 0) {
            return;
        }
        grain(g, w, h, 0.25 + amount * 0.5);
        int bands = (int) (2 + amount * 6);
        g.save();
        for (int i = 0; i < bands; i++) {
            if (random.nextDouble() > amount) {
                continue;
            }
            double y = random.nextDouble() * h;
            double bh = 2 + random.nextDouble() * 18 * amount;
            g.setGlobalAlpha(0.35 + random.nextDouble() * 0.5);
            Image img = noise[random.nextInt(NOISE_FRAMES)];
            g.drawImage(img, 0, random.nextInt(NOISE_SIZE - 4), NOISE_SIZE, 3, 0, y, w, bh);
        }
        g.restore();
    }

    /** Brief whole-frame dips in brightness, as if the supply sagged. */
    public void flicker(GraphicsContext g, double w, double h, double amount) {
        if (amount <= 0 || random.nextDouble() > amount) {
            return;
        }
        g.save();
        g.setGlobalAlpha(0.05 + random.nextDouble() * 0.25 * amount);
        g.setFill(Color.BLACK);
        g.fillRect(0, 0, w, h);
        g.restore();
    }

    /** A stable per-frame random for renderers that want jitter consistent within one frame. */
    public double jitter() {
        return random.nextDouble();
    }
}
