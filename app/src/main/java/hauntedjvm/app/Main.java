package hauntedjvm.app;

import hauntedjvm.app.ui.HauntedApp;
import java.io.IOException;
import java.security.SecureRandom;
import javafx.application.Application;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point. Deliberately not a JavaFX {@code Application} subclass, so {@code --headless}
 * and {@code --help} work on machines without a display and the launcher can be run from a
 * plain classpath.
 */
public final class Main {

    public static final String VERSION = "0.9.0";

    private Main() {
    }

    public static void main(String[] args) {
        LaunchOptions options;
        try {
            options = LaunchOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("hauntedjvm: " + e.getMessage());
            System.err.println();
            System.err.print(LaunchOptions.USAGE);
            System.exit(2);
            return;
        }
        if (options.help()) {
            System.out.print(LaunchOptions.USAGE);
            return;
        }
        if (options.version()) {
            System.out.println("HAUNTEDJVM " + VERSION + " (Java " + Runtime.version() + ")");
            return;
        }
        // Must happen before the first logger is created; logback.xml reads it.
        System.setProperty("HAUNTEDJVM_LOG_LEVEL", options.debug() ? "DEBUG" : "INFO");
        Logger log = LoggerFactory.getLogger(Main.class);
        if (options.headless()) {
            long seed = options.seed().orElseGet(() -> new SecureRandom().nextLong());
            try {
                System.exit(new HeadlessRunner(options, System.out).run(seed));
            } catch (IOException e) {
                log.atError().setCause(e).log("headless run failed");
                System.exit(1);
            }
            return;
        }
        log.atInfo().addKeyValue("version", VERSION).addKeyValue("java", Runtime.version()).log("starting workstation");
        Application.launch(HauntedApp.class, args);
    }
}
