package hauntedjvm.app.ui;

import java.io.IOException;
import java.io.InputStream;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bundled typefaces (SIL Open Font License; licence texts ship next to the font files).
 * IBM Plex Mono for everything that is read; VT323 for the few things that should look like
 * they are burned into a CRT.
 */
public final class Fonts {

    public static final String MONO = "IBM Plex Mono";
    public static final String DISPLAY = "VT323";

    private static final Logger LOG = LoggerFactory.getLogger(Fonts.class);
    private static boolean loaded;

    private Fonts() {
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        for (String file : new String[] {"IBMPlexMono-Regular.ttf", "IBMPlexMono-SemiBold.ttf", "VT323-Regular.ttf"}) {
            try (InputStream in = Fonts.class.getResourceAsStream("/hauntedjvm/app/fonts/" + file)) {
                if (in == null || Font.loadFont(in, 12) == null) {
                    LOG.atWarn().addKeyValue("font", file).log("font not loaded; falling back to system monospace");
                }
            } catch (IOException e) {
                LOG.atWarn().addKeyValue("font", file).setCause(e).log("font not loaded");
            }
        }
        loaded = true;
    }

    public static Font mono(double size) {
        return Font.font(MONO, size);
    }

    public static Font monoBold(double size) {
        return Font.font(MONO, FontWeight.SEMI_BOLD, size);
    }

    public static Font display(double size) {
        return Font.font(DISPLAY, size);
    }
}
