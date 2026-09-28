package hauntedjvm.app.ui;

import hauntedjvm.core.time.FacilityClock;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;

/** Tape-deck controls under the main monitor. */
final class TransportBar extends HBox {

    /** Callbacks, one per control. */
    record Actions(Runnable back, Runnable stepBack, Runnable playPause, Runnable step, Runnable forward,
                   Runnable slower, Runnable faster, Runnable live, Runnable resumeHere) {
    }

    private final Button play;
    private final Button live;
    private final Button resume;
    private final Label speed = Widgets.label("1×", "kv-value");
    private final Label position = Widgets.label("", "dim");

    TransportBar(Actions a) {
        getStyleClass().add("transport");
        setAlignment(Pos.CENTER_LEFT);
        play = control("‖", "Pause / play  [Space]", a.playPause());
        live = control("LIVE", "Return to the live facility  [L]", a.live());
        resume = control("RESUME FROM HERE", "Rewind the recording to the playhead and continue live from there  [R]",
                a.resumeHere());
        resume.getStyleClass().add("danger");
        getChildren().addAll(
                control("⏮", "Back one minute  [Shift+←]", a.back()),
                control("◀◀", "Back ten seconds  [←]", a.stepBack()),
                play,
                control("▶|", "Step one tick  [.]", a.step()),
                control("▶▶", "Forward ten seconds  [→]", a.forward()),
                control("−", "Slower  [ [ ]", a.slower()), speed, control("+", "Faster  [ ] ]", a.faster()),
                Widgets.spacer(), position, live, resume);
    }

    private static Button control(String text, String tip, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add("transport-button");
        b.setFocusTraversable(false);
        b.setTooltip(new Tooltip(tip));
        b.setOnAction(e -> action.run());
        return b;
    }

    void update(Display d, boolean playing, String speedLabel) {
        boolean paused = d.review() ? !playing : d.frame().status().paused();
        play.setText(paused ? "▶" : "‖");
        speed.setText(speedLabel);
        live.setDisable(!d.review());
        live.getStyleClass().remove("active");
        if (!d.review()) {
            live.getStyleClass().add("active");
        }
        resume.setVisible(d.review());
        resume.setManaged(d.review());
        long head = d.frame().status().tick();
        position.setText(d.review()
                ? "playhead " + FacilityClock.format(d.tick()) + "  •  "
                        + FacilityClock.duration(head - d.tick()) + " behind live"
                : "recording " + FacilityClock.duration(head));
    }
}
