package hauntedjvm.app.ui;

import hauntedjvm.app.runtime.Speed;
import hauntedjvm.app.session.Session;
import hauntedjvm.audio.AudioEngine;
import hauntedjvm.core.Simulations;
import hauntedjvm.core.config.SimulationConfig;
import hauntedjvm.core.engine.SimulationEngine;
import hauntedjvm.core.entity.CameraFacet;
import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityKind;
import hauntedjvm.core.entity.EntityState;
import hauntedjvm.core.world.FacilityMap;
import hauntedjvm.diagnostics.TelemetryService;
import hauntedjvm.persistence.SessionArchive;
import hauntedjvm.persistence.SessionLibrary;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javax.imageio.ImageIO;

/**
 * Renders a fixed set of workstation scenes to PNG, for the README and for checking visual
 * changes. Development tool only: run with {@code ./gradlew :app:screenshots}; it is not part
 * of the application's command line.
 *
 * <p>Scenes come from a seeded night fast-forwarded headlessly, so the images are the same on
 * every run apart from film grain.
 */
public final class DemoScenes extends Application implements AppServices {

    private static final long SEED = 0x00C0FFEEL;

    private final ExecutorService io = Executors.newVirtualThreadPerTaskExecutor();
    private final Deque<Runnable> steps = new ArrayDeque<>();
    private TelemetryService telemetry;
    private Stage stage;
    private Workstation workstation;
    private Path out;

    /** Plain entry point: JavaFX refuses a classpath launch whose main class is an Application. */
    public static final class Launcher {
        private Launcher() {
        }

        public static void main(String[] args) {
            launch(DemoScenes.class, args);
        }
    }

    @Override
    public void start(Stage primary) throws IOException {
        stage = primary;
        out = Path.of(getParameters().getRaw().isEmpty() ? "docs/screenshots" : getParameters().getRaw().getFirst());
        Files.createDirectories(out);
        Fonts.load();
        telemetry = TelemetryService.start(Duration.ofMillis(250), 60, true);

        SimulationEngine engine = Simulations.create(SimulationConfig.defaults(SEED).withAnomalyIntensity(2.5));
        engine.run(5_200);
        Session session = Session.fromEngine(engine, Speed.NORMAL, Speed.BASE_TICKS_PER_SECOND, false);
        workstation = new Workstation(this, session, Speed.NORMAL);
        workstation.skipBoot();
        StackPane root = new StackPane(workstation.root());
        Scene scene = new Scene(root, 1600, 960);
        scene.getStylesheets().add(DemoScenes.class.getResource("/hauntedjvm/app/workstation.css").toExternalForm());
        stage.setScene(scene);
        stage.setTitle("HAUNTEDJVM demo scenes");
        stage.show();

        var world = engine.world();
        Entity person = world.ofKind(EntityKind.PERSON).stream()
                .filter(p -> p.present() && p.state() != EntityState.NORMAL).findFirst()
                .orElse(world.ofKind(EntityKind.PERSON).getFirst());
        String busiest = world.ofKind(EntityKind.CAMERA).stream()
                .max(java.util.Comparator.comparingInt(c -> world.occupants(((CameraFacet) c.facet()).roomCode()).size()))
                .map(Entity::name).orElse("CAM-02");
        String dark = world.ofKind(EntityKind.CAMERA).stream()
                .filter(c -> world.light(((CameraFacet) c.facet()).roomCode()).dark())
                .map(Entity::name).findFirst().orElse(null);

        steps.add(() -> workstation.view(new Feed.Camera(busiest)));
        steps.add(() -> shoot(scene, "01-camera"));
        steps.add(() -> {
            workstation.view(new Feed.Floorplan());
            workstation.select(new Selection.OfEntity(person.id()));
            workstation.toggleTrace(person.id());
        });
        steps.add(() -> shoot(scene, "02-floorplan-inspector"));
        steps.add(() -> workstation.view(new Feed.SystemTable()));
        steps.add(() -> shoot(scene, "03-process-table"));
        if (dark != null) {
            steps.add(() -> workstation.view(new Feed.Camera(dark)));
            steps.add(() -> shoot(scene, "04-infrared"));
        }
        steps.add(() -> {
            workstation.view(new Feed.Camera(busiest));
            workstation.jumpTo(3_000, -1);
        });
        steps.add(() -> shoot(scene, "05-review"));
        steps.add(() -> workstation.jumpTo(Long.MAX_VALUE, -1));
        steps.add(() -> workstation.view(new Feed.Camera(busiest)));
        steps.add(() -> { });
        steps.add(() -> { });
        steps.add(() -> System.out.printf("steady-state render: %.0f fps%n", workstation.fps()));
        steps.add(Platform::exit);
        next();
    }

    private void next() {
        Runnable step = steps.poll();
        if (step == null) {
            return;
        }
        PauseTransition wait = new PauseTransition(javafx.util.Duration.millis(1_600));
        wait.setOnFinished(e -> {
            step.run();
            next();
        });
        wait.play();
    }

    private void shoot(Scene scene, String name) {
        WritableImage image = scene.snapshot(null);
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        BufferedImage buffered = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        PixelReader reader = image.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                buffered.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        try {
            ImageIO.write(buffered, "png", out.resolve(name + ".png").toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void stop() {
        workstation.close();
        telemetry.close();
        io.shutdown();
    }

    @Override
    public TelemetryService telemetry() {
        return telemetry;
    }

    @Override
    public AudioEngine audio() {
        return AudioEngine.silent();
    }

    @Override
    public Stage stage() {
        return stage;
    }

    @Override
    public boolean debug() {
        return false;
    }

    @Override
    public ExecutorService io() {
        return io;
    }

    @Override
    public SessionLibrary library() {
        return SessionLibrary.inUserHome();
    }

    @Override
    public SessionArchive archive() {
        return new SessionArchive(FacilityMap.facility07());
    }

    @Override
    public void newSession(long seed, int persons) {
        throw new UnsupportedOperationException("demo scenes are fixed");
    }

    @Override
    public void open(Session session, String message) {
        throw new UnsupportedOperationException("demo scenes are fixed");
    }
}
