package hauntedjvm.app.ui;

import hauntedjvm.app.LaunchOptions;
import hauntedjvm.app.runtime.Speed;
import hauntedjvm.app.session.Session;
import hauntedjvm.audio.AudioEngine;
import hauntedjvm.core.config.SimulationConfig;
import hauntedjvm.core.world.FacilityMap;
import hauntedjvm.diagnostics.TelemetryService;
import hauntedjvm.persistence.Investigation;
import hauntedjvm.persistence.SessionArchive;
import hauntedjvm.persistence.SessionLibrary;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** JavaFX lifecycle: shared services, the window, and swapping sessions in and out. */
public final class HauntedApp extends Application implements AppServices {

    private static final Logger LOG = LoggerFactory.getLogger(HauntedApp.class);

    private LaunchOptions options;
    private TelemetryService telemetry;
    private AudioEngine audio;
    private ExecutorService io;
    private final SessionLibrary library = SessionLibrary.inUserHome();
    private final SessionArchive archive = new SessionArchive(FacilityMap.facility07());
    private Stage stage;
    private final StackPane root = new StackPane();
    private Workstation current;

    @Override
    public void init() {
        options = LaunchOptions.parse(getParameters().getRaw().toArray(String[]::new));
    }

    @Override
    public void start(Stage primary) {
        this.stage = primary;
        Fonts.load();
        io = Executors.newVirtualThreadPerTaskExecutor();
        telemetry = TelemetryService.start(Duration.ofSeconds(1), 120, true);
        audio = AudioEngine.open(options.mute());
        Scene scene = new Scene(root, 1500, 920);
        scene.getStylesheets().add(HauntedApp.class.getResource("/hauntedjvm/app/workstation.css").toExternalForm());
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (current != null && current.onKey(e)) {
                e.consume();
            }
        });
        stage.setScene(scene);
        stage.setTitle("HAUNTEDJVM — FACILITY-07");
        stage.setMinWidth(1100);
        stage.setMinHeight(700);
        stage.setOnCloseRequest(e -> Platform.exit());

        if (options.load() != null) {
            try {
                Investigation inv = archive.load(options.load());
                open(Session.reopen(inv, speed(), Speed.BASE_TICKS_PER_SECOND), "loaded " + options.load().getFileName());
            } catch (IOException | RuntimeException e) {
                LOG.atError().setCause(e).log("could not load session");
                startFresh(true);
            }
        } else {
            startFresh(true);
        }
        stage.show();
    }

    private Speed speed() {
        return Speed.closest(options.speed());
    }

    private void startFresh(boolean boot) {
        long seed = options.seed().orElseGet(() -> new SecureRandom().nextLong());
        SimulationConfig config = SimulationConfig.defaults(seed).withPersons(options.persons());
        Session s = Session.fresh(config, speed(), Speed.BASE_TICKS_PER_SECOND);
        swap(s);
        if (boot) {
            current.boot();
        } else {
            current.skipBoot();
        }
    }

    private void swap(Session session) {
        if (current != null) {
            current.close();
        }
        current = new Workstation(this, session, speed());
        root.getChildren().setAll(current.root());
        LOG.atInfo().addKeyValue("seed", session.config().seedHex()).log("session opened");
    }

    @Override
    public void newSession(long seed, int persons) {
        swap(Session.fresh(SimulationConfig.defaults(seed).withPersons(persons), speed(), Speed.BASE_TICKS_PER_SECOND));
        current.boot();
    }

    @Override
    public void open(Session session, String message) {
        swap(session);
        current.skipBoot();
    }

    @Override
    public void stop() {
        if (current != null) {
            current.close();
        }
        if (telemetry != null) {
            telemetry.close();
        }
        if (audio != null) {
            audio.close();
        }
        if (io != null) {
            io.shutdown();
        }
    }

    @Override
    public TelemetryService telemetry() {
        return telemetry;
    }

    @Override
    public AudioEngine audio() {
        return audio;
    }

    @Override
    public Stage stage() {
        return stage;
    }

    @Override
    public boolean debug() {
        return options.debug();
    }

    @Override
    public ExecutorService io() {
        return io;
    }

    @Override
    public SessionLibrary library() {
        return library;
    }

    @Override
    public SessionArchive archive() {
        return archive;
    }
}
