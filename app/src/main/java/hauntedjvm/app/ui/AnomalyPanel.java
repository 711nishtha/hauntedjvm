package hauntedjvm.app.ui;

import hauntedjvm.app.render.Palette;
import hauntedjvm.app.session.Session;
import hauntedjvm.core.incident.AnomalyRecord;
import hauntedjvm.core.incident.DetectedAnomaly;
import hauntedjvm.core.time.FacilityClock;
import java.util.List;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/** Every anomaly the incident system has logged in this timeline, newest first. */
final class AnomalyPanel extends BorderPane {

    private final ObservableList<AnomalyRecord> items = FXCollections.observableArrayList();
    private final ListView<AnomalyRecord> list = new ListView<>(items);
    private final CheckBox openOnly = new CheckBox("open only");
    private final Label count = Widgets.label("", "dim");
    private final Session session;
    private List<AnomalyRecord> last;

    AnomalyPanel(Operator operator, Session session) {
        this.session = session;
        list.setCellFactory(v -> new Cell());
        list.setOnMouseClicked(e -> {
            AnomalyRecord r = list.getSelectionModel().getSelectedItem();
            if (r != null) {
                operator.select(new Selection.OfAnomaly(r.anomaly().id()));
            }
        });
        openOnly.setOnAction(e -> last = null);
        HBox top = new HBox(10, count, Widgets.spacer(), openOnly);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(6, 10, 6, 10));
        setTop(top);
        setCenter(list);
    }

    void refresh(Display d) {
        List<AnomalyRecord> all = d.world().incident().anomalies();
        if (all == last) {
            return;
        }
        last = all;
        List<AnomalyRecord> shown = all.stream().filter(r -> !openOnly.isSelected() || r.active()).toList().reversed();
        items.setAll(shown);
        long open = all.stream().filter(AnomalyRecord::active).count();
        count.setText(all.size() + " logged, " + open + " open");
    }

    private final class Cell extends ListCell<AnomalyRecord> {
        private final Label time = Widgets.label("", "memory-time");
        private final Label tag = Widgets.label("", "tag");
        private final Label text = Widgets.label("", "memory-note");
        private final VBox box = new VBox(2, new HBox(8, time, tag), text);

        Cell() {
            text.setWrapText(true);
            text.maxWidthProperty().bind(widthProperty().subtract(24));
            box.setPadding(new Insets(3, 0, 3, 0));
        }

        @Override
        protected void updateItem(AnomalyRecord r, boolean empty) {
            super.updateItem(r, empty);
            if (empty || r == null) {
                setGraphic(null);
                return;
            }
            DetectedAnomaly a = r.anomaly();
            boolean withdrawn = r.status() == AnomalyRecord.Status.RETRACTED;
            time.setText(FacilityClock.format(a.tick()) + "  " + r.status().name().toLowerCase()
                    + (session.discovered(a.id()) ? "  ✓ examined" : ""));
            tag.setText(a.category() + " " + "■".repeat(a.severity()));
            tag.setStyle("-fx-text-fill: " + Palette.hex(Palette.category(a.category())) + "; -fx-border-color: "
                    + Palette.hex(Palette.category(a.category()).darker()) + ";");
            text.setText(withdrawn ? "████ RECORD WITHDRAWN ████" : a.summary());
            text.setOpacity(r.active() ? 1 : 0.55);
            setGraphic(box);
        }
    }
}
