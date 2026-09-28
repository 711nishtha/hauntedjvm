package hauntedjvm.app.ui;

import hauntedjvm.app.render.Palette;
import hauntedjvm.core.config.SimulationConfig;
import hauntedjvm.core.incident.EscalationLevel;
import hauntedjvm.core.time.FacilityClock;
import java.util.ArrayList;
import java.util.List;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** Title, seed, escalation, mode and clock; plus the session menu. */
final class HeaderBar extends HBox {

    /** Menu callbacks. */
    record Actions(Runnable newSession, Runnable save, Runnable load, Runnable export, Runnable help,
                   Runnable toggleSound) {
    }

    private final Label brandSub = Widgets.label("", "brand-sub");
    private final Label mode = Widgets.label("LIVE", "mode-badge", "mode-live");
    private final Label clock = Widgets.label("--:--:--", "clock");
    private final Label levelText = Widgets.label("", "brand-sub");
    private final List<Region> segments = new ArrayList<>();
    private final Button sound;

    HeaderBar(Actions actions) {
        getStyleClass().add("header");
        setAlignment(Pos.CENTER_LEFT);
        Label brand = Widgets.label("HAUNTEDJVM", "brand");
        VBox titles = new VBox(-4, brand, brandSub);

        HBox level = new HBox(3);
        level.setAlignment(Pos.CENTER_LEFT);
        for (int i = 0; i < 6; i++) {
            Region r = new Region();
            r.setMinSize(16, 9);
            r.setMaxSize(16, 9);
            segments.add(r);
            level.getChildren().add(r);
        }
        VBox levelBox = new VBox(3, level, levelText);
        levelBox.setAlignment(Pos.CENTER_LEFT);

        sound = menu("SOUND", actions.toggleSound());
        HBox menu = new HBox(2, menu("NEW", actions.newSession()), menu("SAVE", actions.save()),
                menu("LOAD", actions.load()), menu("EXPORT", actions.export()), sound, menu("HELP", actions.help()));
        menu.setAlignment(Pos.CENTER_RIGHT);
        VBox right = new VBox(0, new HBox(12, mode, clock), menu);
        right.setAlignment(Pos.CENTER_RIGHT);
        ((HBox) right.getChildren().getFirst()).setAlignment(Pos.CENTER_RIGHT);
        getChildren().addAll(titles, Widgets.spacer(), levelBox, Widgets.spacer(), right);
    }

    private static Button menu(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add("menu-link");
        b.setFocusTraversable(false);
        b.setOnAction(e -> action.run());
        return b;
    }

    void setSession(SimulationConfig config) {
        String incident = String.format("%04X", config.seed() & 0xFFFF);
        brandSub.setText("FACILITY-07 // INCIDENT 0x" + incident + " // SEED " + config.seedHex() + " // "
                + config.persons() + " STAFF");
    }

    void setSound(boolean on) {
        sound.setText(on ? "SOUND ON" : "SOUND OFF");
    }

    void update(Display d, boolean playing) {
        clock.setText(FacilityClock.format(d.tick()));
        String badge;
        String style;
        if (d.frame().status().error() != null) {
            badge = "✖ HALTED";
            style = "mode-error";
        } else if (d.review()) {
            badge = playing ? "▶ PLAYBACK" : "◀ REVIEW";
            style = "mode-review";
        } else if (d.frame().status().paused()) {
            badge = "‖ PAUSED";
            style = "mode-paused";
        } else {
            badge = "● LIVE " + d.frame().status().speed().label();
            style = "mode-live";
        }
        mode.setText(badge);
        mode.getStyleClass().removeAll("mode-live", "mode-paused", "mode-review", "mode-error");
        mode.getStyleClass().add(style);
        EscalationLevel level = d.world().incident().escalation();
        levelText.setText(level.label());
        levelText.setTextFill(Palette.level(level.number()));
        for (int i = 0; i < segments.size(); i++) {
            String color = i <= level.number() ? Palette.hex(Palette.level(i)) : "#161c19";
            segments.get(i).setStyle("-fx-background-color: " + color + ";");
        }
    }
}
