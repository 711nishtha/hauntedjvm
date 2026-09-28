package hauntedjvm.app.ui;

import hauntedjvm.app.render.Palette;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

/** Small building blocks shared by the panels. */
final class Widgets {

    private Widgets() {
    }

    static Label label(String text, String... styles) {
        Label l = new Label(text);
        l.getStyleClass().addAll(styles);
        return l;
    }

    /** A bordered tag. {@code kind} is one of real, sim, fiction, alarm. */
    static Label tag(String text, String kind) {
        return label(text, "tag", "tag-" + kind);
    }

    static Button button(String text, Runnable action, String... styles) {
        Button b = new Button(text);
        b.getStyleClass().add("flat-button");
        b.getStyleClass().addAll(styles);
        b.setFocusTraversable(false);
        b.setOnAction(e -> action.run());
        return b;
    }

    static HBox sectionTitle(String text, String styleSuffix, Label tag) {
        Label title = label(text, "section-title", "section-title-" + styleSuffix);
        HBox box = new HBox(8, title);
        box.setAlignment(Pos.CENTER_LEFT);
        if (tag != null) {
            box.getChildren().add(tag);
        }
        return box;
    }

    static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    /**
     * A two-column key/value table whose value labels are created once and updated in place.
     * Rebuilding label nodes four times a second would churn the scene graph for nothing.
     */
    static final class KeyValues extends GridPane {
        private final Map<String, Label> values = new LinkedHashMap<>();

        KeyValues() {
            setHgap(12);
            setVgap(2);
            setPadding(new Insets(0, 10, 6, 10));
            ColumnConstraints key = new ColumnConstraints();
            key.setMinWidth(Region.USE_PREF_SIZE);
            ColumnConstraints value = new ColumnConstraints();
            value.setHgrow(Priority.ALWAYS);
            getColumnConstraints().addAll(key, value);
        }

        void set(String key, String value) {
            set(key, value, null);
        }

        /** @param tone null, "warn" or "bad" */
        void set(String key, String value, String tone) {
            Label v = values.get(key);
            if (v == null) {
                v = label(value, "kv-value");
                v.setWrapText(true);
                int row = values.size();
                add(label(key, "kv-key"), 0, row);
                add(v, 1, row);
                values.put(key, v);
            }
            if (!v.getText().equals(value)) {
                v.setText(value);
            }
            v.getStyleClass().removeAll("warn", "bad");
            if (tone != null) {
                v.getStyleClass().add(tone);
            }
        }

        void clear() {
            getChildren().clear();
            values.clear();
        }
    }

    /** A tiny line chart for telemetry history. */
    static final class Sparkline extends Canvas {
        private final Color color;

        Sparkline(double width, double height, Color color) {
            super(width, height);
            this.color = color;
        }

        void update(List<Double> values, double max) {
            GraphicsContext g = getGraphicsContext2D();
            double w = getWidth();
            double h = getHeight();
            g.clearRect(0, 0, w, h);
            g.setFill(Palette.PANEL_RAISED);
            g.fillRect(0, 0, w, h);
            if (values.size() < 2 || max <= 0) {
                return;
            }
            double step = w / (values.size() - 1);
            g.setStroke(color);
            g.setLineWidth(1);
            g.beginPath();
            for (int i = 0; i < values.size(); i++) {
                double y = h - 1 - Math.min(1, Math.max(0, values.get(i) / max)) * (h - 2);
                if (i == 0) {
                    g.moveTo(0, y);
                } else {
                    g.lineTo(i * step, y);
                }
            }
            g.stroke();
            g.setFill(color.deriveColor(0, 1, 1, 0.12));
            g.lineTo(w, h);
            g.lineTo(0, h);
            g.closePath();
            g.fill();
        }
    }

    /** A thin horizontal gauge with an optional warning threshold. */
    static final class Meter extends Canvas {
        Meter(double width) {
            super(width, 5);
        }

        void update(double fraction, Color color) {
            GraphicsContext g = getGraphicsContext2D();
            g.setFill(Palette.LINE);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setFill(color);
            g.fillRect(0, 0, getWidth() * Math.clamp(fraction, 0.0, 1.0), getHeight());
        }
    }
}
