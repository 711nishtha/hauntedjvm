package hauntedjvm.app.ui;

import hauntedjvm.app.render.Palette;
import java.util.List;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Everything that floats above the workstation: toasts, the escalation banner, help, and the
 * boot sequence. Mouse-transparent except while a card is open.
 */
final class Overlays extends StackPane {

    private final VBox toasts = new VBox(6);
    private final Label banner = Widgets.label("", "banner");
    private Node card;
    private Runnable onCardClosed;

    Overlays() {
        setPickOnBounds(false);
        toasts.setAlignment(Pos.TOP_RIGHT);
        toasts.setPadding(new Insets(70, 16, 0, 0));
        toasts.setMouseTransparent(true);
        toasts.setPickOnBounds(false);
        StackPane.setAlignment(toasts, Pos.TOP_RIGHT);
        toasts.setMaxSize(460, 400);
        banner.setVisible(false);
        banner.setMouseTransparent(true);
        getChildren().addAll(toasts, banner);
    }

    /** @param kind ok, warn or bad */
    void toast(String text, String kind) {
        Label t = Widgets.label(text, "toast", "toast-" + kind);
        t.setWrapText(true);
        t.setMaxWidth(440);
        toasts.getChildren().addFirst(t);
        if (toasts.getChildren().size() > 5) {
            toasts.getChildren().removeLast();
        }
        FadeTransition fade = new FadeTransition(Duration.millis(700), t);
        fade.setFromValue(1);
        fade.setToValue(0);
        SequentialTransition life = new SequentialTransition(new PauseTransition(Duration.seconds(4)), fade);
        life.setOnFinished(e -> toasts.getChildren().remove(t));
        life.play();
    }

    /** A large flickering announcement, for escalation. */
    void banner(String text, javafx.scene.paint.Color color) {
        banner.setText(text);
        banner.setTextFill(color);
        banner.setStyle("-fx-border-color: " + Palette.hex(color) + ";");
        banner.setVisible(true);
        banner.setOpacity(1);
        Timeline flicker = new Timeline();
        double[] beats = {0, 60, 120, 200, 260, 420};
        for (int i = 0; i < beats.length; i++) {
            double opacity = i % 2 == 0 ? 1 : 0.15;
            flicker.getKeyFrames().add(new KeyFrame(Duration.millis(beats[i]), e -> banner.setOpacity(opacity)));
        }
        FadeTransition fade = new FadeTransition(Duration.millis(900), banner);
        fade.setFromValue(1);
        fade.setToValue(0);
        SequentialTransition seq = new SequentialTransition(flicker, new PauseTransition(Duration.seconds(2.2)), fade);
        seq.setOnFinished(e -> banner.setVisible(false));
        seq.play();
    }

    boolean cardOpen() {
        return card != null;
    }

    void closeCard() {
        if (card != null) {
            getChildren().remove(card);
            card = null;
            Runnable after = onCardClosed;
            onCardClosed = null;
            if (after != null) {
                after.run();
            }
        }
    }

    void help() {
        if (card != null) {
            closeCard();
            return;
        }
        GridPane keys = new GridPane();
        keys.setHgap(14);
        keys.setVgap(5);
        String[][] rows = {
            {"Space", "pause / resume (or play back while reviewing)"},
            {"←  →", "scrub back / forward ten seconds (Shift: one minute)"},
            {".", "step one tick"},
            {"[  ]", "slower / faster"},
            {"L", "back to live"},
            {"R", "rewind the recording to the playhead and resume from there"},
            {"1–8", "camera feeds    F  floor plan    S  process table"},
            {"click", "inspect a person, door, room, event or anomaly"},
            {"W", "follow the selected person across cameras"},
            {"T", "trace the selected person's relationships"},
            {"N", "write a note about the selection"},
            {"M", "sound on / off"},
            {"Ctrl+S / Ctrl+O", "save / load the investigation"},
            {"Ctrl+E", "export an incident report (Markdown)"},
            {"Ctrl+N", "start a new night with a new seed"},
            {"F1 / ?", "this card    Esc  close / clear selection"},
        };
        for (int i = 0; i < rows.length; i++) {
            keys.add(Widgets.label(rows[i][0], "key-cap"), 0, i);
            keys.add(Widgets.label(rows[i][1], "overlay-text"), 1, i);
        }
        VBox box = new VBox(12, Widgets.label("CONTROLS", "overlay-title"), keys,
                Widgets.label("Everything you do is recorded, including where you look.", "dim"));
        show(box, null);
    }

    /** The first-launch sequence: a few lines of boot text, then the operator briefing. */
    void boot(String seedHex, int staff, Runnable done) {
        VBox lines = new VBox(3);
        lines.setAlignment(Pos.TOP_LEFT);
        lines.setPadding(new Insets(40));
        StackPane screen = new StackPane(lines);
        screen.setStyle("-fx-background-color: #030403;");
        StackPane.setAlignment(lines, Pos.TOP_LEFT);
        List<String> text = List.of(
                "HAUNTEDJVM MONITORING STATION  v0.9",
                "(c) FACILITY-07 NIGHT OPERATIONS",
                "",
                "checking memory ................... ok",
                "linking camera multiplexer ........ 8 feeds",
                "badge tracker ..................... " + staff + " badges",
                "process table ..................... 8 processes",
                "incident log ...................... open",
                "",
                "seed " + seedHex,
                "",
                "RECORDING");
        Timeline typing = new Timeline();
        for (int i = 0; i < text.size(); i++) {
            String line = text.get(i);
            typing.getKeyFrames().add(new KeyFrame(Duration.millis(120 + i * 140L), e -> {
                Label l = Widgets.label(line, "boot-line");
                if (line.equals("RECORDING")) {
                    l.setTextFill(Palette.RED);
                }
                lines.getChildren().add(l);
            }));
        }
        typing.getKeyFrames().add(new KeyFrame(Duration.millis(120 + text.size() * 140L + 500), e -> {
            getChildren().remove(screen);
            card = null;
            briefing(done);
        }));
        screen.setOnMouseClicked(e -> {
            typing.stop();
            getChildren().remove(screen);
            card = null;
            briefing(done);
        });
        card = screen;
        getChildren().add(screen);
        typing.play();
    }

    private void briefing(Runnable done) {
        VBox box = new VBox(10,
                Widgets.label("NIGHT OPERATOR BRIEFING", "overlay-title"),
                para("You are watching FACILITY-07 through its security system: eight camera feeds, a badge tracker, "
                        + "and the facility's process table. The night shift is going about its routine."),
                para("Your job is to watch, and to write down anything that does not make sense. Everything is "
                        + "recorded. You can pause, rewind and replay the recording to examine what happened."),
                para("The badge tracker shows where badges are. The cameras show what is there. "
                        + "Those are not always the same thing."),
                para("Click anyone to inspect them. Press F1 at any time for the controls."),
                Widgets.label("[ click or press any key to begin ]", "phosphor"));
        show(box, done);
    }

    private static Label para(String text) {
        Label l = Widgets.label(text, "overlay-text");
        l.setWrapText(true);
        l.setMaxWidth(560);
        return l;
    }

    private void show(VBox content, Runnable closed) {
        content.getStyleClass().add("overlay-card");
        content.setMaxSize(640, Region.USE_PREF_SIZE);
        StackPane scrim = new StackPane(content);
        scrim.setStyle("-fx-background-color: rgba(0,0,0,0.55);");
        scrim.setOnMouseClicked(e -> closeCard());
        card = scrim;
        onCardClosed = closed;
        getChildren().add(scrim);
    }
}
