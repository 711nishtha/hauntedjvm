package hauntedjvm.app.ui;

import hauntedjvm.app.session.Session;
import hauntedjvm.core.config.SimulationConfig;
import hauntedjvm.core.state.WorldView;
import hauntedjvm.persistence.IncidentReport;
import hauntedjvm.persistence.Investigation;
import hauntedjvm.persistence.SessionArchive;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * New / save / load / export. File I/O runs on virtual threads; results come back to the
 * JavaFX thread as toasts. Files are only ever read or written where the operator points
 * the file dialog (defaulting to {@code ~/.hauntedjvm/sessions}).
 */
final class SessionDialogs {

    private static final Logger LOG = LoggerFactory.getLogger(SessionDialogs.class);

    private final AppServices app;
    private final Session session;
    private final Overlays overlays;
    private final Supplier<WorldView> live;

    SessionDialogs(AppServices app, Session session, Overlays overlays, Supplier<WorldView> live) {
        this.app = app;
        this.session = session;
        this.overlays = overlays;
        this.live = live;
    }

    void newSession() {
        Dialog<ButtonType> dialog = styled(new Dialog<>(), "NEW NIGHT");
        TextField seed = new TextField(String.format("0x%016X", new SecureRandom().nextLong()));
        TextField staff = new TextField(String.valueOf(session.config().persons()));
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(12));
        grid.addRow(0, new Label("seed"), seed);
        grid.addRow(1, new Label("staff"), staff);
        grid.add(new Label("Any word works as a seed. The same seed replays the same night."), 0, 2, 2, 1);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(new ButtonType("BEGIN", ButtonBar.ButtonData.OK_DONE),
                ButtonType.CANCEL);
        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get().getButtonData() != ButtonBar.ButtonData.OK_DONE) {
            return;
        }
        try {
            long s = hauntedjvm.app.LaunchOptions.parse("--seed", seed.getText(), "--entities", staff.getText())
                    .seed().orElseThrow();
            int n = Integer.parseInt(staff.getText().strip());
            app.newSession(s, Math.clamp(n, 1, SimulationConfig.MAX_PERSONS));
        } catch (IllegalArgumentException e) {
            overlays.toast(e.getMessage(), "bad");
        }
    }

    void save() {
        FileChooser chooser = chooser("Save investigation", "HAUNTEDJVM session", "*" + SessionArchive.EXTENSION);
        chooser.setInitialFileName(app.library().newFile(session.config().seed()).getFileName().toString());
        File file = chooser.showSaveDialog(app.stage());
        if (file == null) {
            return;
        }
        overlays.toast("saving…", "ok");
        session.capture().thenAcceptAsync(inv -> {
            try {
                app.archive().save(inv, file.toPath());
                Platform.runLater(() -> {
                    session.markSaved();
                    overlays.toast("saved " + file.getName() + " (" + inv.eventCount() + " events)", "ok");
                });
            } catch (IOException e) {
                fail("save failed", e);
            }
        }, app.io());
    }

    void load() {
        FileChooser chooser = chooser("Open investigation", "HAUNTEDJVM session", "*" + SessionArchive.EXTENSION);
        File file = chooser.showOpenDialog(app.stage());
        if (file == null) {
            return;
        }
        overlays.toast("replaying " + file.getName() + "…", "ok");
        CompletableFuture.runAsync(() -> {
            try {
                Investigation inv = app.archive().load(file.toPath());
                Platform.runLater(() -> app.open(Session.reopen(inv, hauntedjvm.app.runtime.Speed.NORMAL,
                        hauntedjvm.app.runtime.Speed.BASE_TICKS_PER_SECOND),
                        "loaded " + file.getName() + ": replay verified, paused at the end of the recording"));
            } catch (IOException | RuntimeException e) {
                fail("could not open " + file.getName(), e);
            }
        }, app.io());
    }

    void export() {
        FileChooser chooser = chooser("Export incident report", "Markdown", "*.md");
        chooser.setInitialFileName("incident-" + String.format("%016X", session.config().seed()) + ".md");
        File file = chooser.showSaveDialog(app.stage());
        if (file == null) {
            return;
        }
        session.capture().thenAcceptAsync(inv -> {
            try {
                WorldView head = inv.timeline().reconstruct(live.get().map(), inv.headTick());
                IncidentReport.write(inv, head, file.toPath());
                Platform.runLater(() -> overlays.toast("report written to " + file.getName(), "ok"));
            } catch (IOException e) {
                fail("export failed", e);
            }
        }, app.io());
    }

    void confirm(String title, String text, String action, Runnable onConfirm) {
        Alert alert = styled(new Alert(Alert.AlertType.NONE), title);
        alert.setContentText(text);
        ButtonType go = new ButtonType(action, ButtonBar.ButtonData.OK_DONE);
        alert.getButtonTypes().setAll(go, ButtonType.CANCEL);
        alert.showAndWait().filter(b -> b == go).ifPresent(b -> onConfirm.run());
    }

    private <T extends Dialog<?>> T styled(T dialog, String title) {
        dialog.initOwner(app.stage());
        dialog.setTitle(title);
        dialog.setHeaderText(title);
        dialog.getDialogPane().getStylesheets().addAll(app.stage().getScene().getStylesheets());
        return dialog;
    }

    private FileChooser chooser(String title, String description, String pattern) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(description, pattern));
        Path dir = app.library().directory();
        try {
            Files.createDirectories(dir);
            chooser.setInitialDirectory(dir.toFile());
        } catch (IOException e) {
            LOG.atDebug().addKeyValue("dir", dir).log("session directory unavailable");
        }
        return chooser;
    }

    private void fail(String what, Exception e) {
        LOG.atWarn().setCause(e).log(what);
        Platform.runLater(() -> overlays.toast(what + ": " + e.getMessage(), "bad"));
    }
}
