package hauntedjvm.app.ui;

import java.util.LinkedHashMap;
import java.util.Map;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;

/** Right-hand tabbed panel. Hand-rolled tabs, because TabPane's chrome fights the aesthetic. */
final class SidePanel extends BorderPane {

    enum Tab { TELEMETRY, INSPECT, ANOMALIES, NOTES }

    private final Map<Tab, Node> pages = new LinkedHashMap<>();
    private final Map<Tab, Button> buttons = new LinkedHashMap<>();
    private Tab current;

    SidePanel(Node telemetry, Node inspector, Node anomalies, Node notes) {
        pages.put(Tab.TELEMETRY, telemetry);
        pages.put(Tab.INSPECT, inspector);
        pages.put(Tab.ANOMALIES, anomalies);
        pages.put(Tab.NOTES, notes);
        HBox strip = new HBox();
        strip.getStyleClass().add("tab-strip");
        for (Tab t : Tab.values()) {
            Button b = new Button(t.name());
            b.getStyleClass().add("tab-button");
            b.setFocusTraversable(false);
            b.setOnAction(e -> show(t));
            buttons.put(t, b);
            strip.getChildren().add(b);
        }
        setTop(strip);
        getStyleClass().add("panel");
        setMinWidth(300);
        setPrefWidth(380);
        show(Tab.TELEMETRY);
    }

    void show(Tab tab) {
        current = tab;
        setCenter(pages.get(tab));
        buttons.forEach((t, b) -> {
            b.getStyleClass().remove("selected");
            if (t == tab) {
                b.getStyleClass().add("selected");
            }
        });
    }

    Tab current() {
        return current;
    }
}
