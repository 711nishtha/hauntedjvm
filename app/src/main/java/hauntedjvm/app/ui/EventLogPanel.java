package hauntedjvm.app.ui;

import hauntedjvm.app.render.Palette;
import hauntedjvm.core.event.EventLog;
import hauntedjvm.core.event.EventRecord;
import hauntedjvm.core.state.WorldView;
import hauntedjvm.core.time.FacilityClock;
import hauntedjvm.core.timeline.Timeline;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;

/**
 * The event log. Reads the timeline incrementally: each frame it looks only at records appended
 * since the last one it saw, filters them, and appends the survivors. The view holds at most
 * {@link #MAX_ROWS} rows; the full history is always one click away through the timeline.
 */
final class EventLogPanel extends BorderPane {

    private static final int MAX_ROWS = 4_000;
    private static final int MAX_PER_FRAME = 20_000;

    /** A log row: a record plus its text, rendered once. */
    record Row(EventRecord record, String time, String text, EventText.Group group) {
    }

    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final ListView<Row> list = new ListView<>(rows);
    private final CheckBox hideRoutine = new CheckBox("hide routine");
    private final TextField search = new TextField();
    private final Set<EventText.Group> groups = EnumSet.allOf(EventText.Group.class);
    private final Operator operator;
    private final boolean debug;
    private Timeline timeline;
    private long scannedTo;
    private boolean follow = true;

    EventLogPanel(Operator operator, boolean debug) {
        this.operator = operator;
        this.debug = debug;
        hideRoutine.setSelected(true);
        hideRoutine.setOnAction(e -> reset());
        search.setPromptText("filter…");
        search.setPrefColumnCount(14);
        search.textProperty().addListener((o, a, b) -> reset());
        HBox filters = new HBox(10);
        filters.setAlignment(Pos.CENTER_LEFT);
        filters.setPadding(new Insets(4, 10, 4, 10));
        filters.getChildren().add(Widgets.label("EVENT LOG", "section-title"));
        for (EventText.Group g : EventText.Group.values()) {
            CheckBox box = new CheckBox(g.name().toLowerCase(Locale.ROOT));
            box.setSelected(true);
            box.setOnAction(e -> {
                if (box.isSelected()) {
                    groups.add(g);
                } else {
                    groups.remove(g);
                }
                reset();
            });
            filters.getChildren().add(box);
        }
        filters.getChildren().addAll(Widgets.spacer(), hideRoutine, search);
        setTop(filters);
        list.setCellFactory(v -> new Cell());
        list.setOnMouseClicked(e -> {
            Row r = list.getSelectionModel().getSelectedItem();
            if (r == null) {
                return;
            }
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                operator.jumpTo(r.record().tick(), r.record().seq());
            } else {
                operator.select(new Selection.OfEvent(r.record().seq()));
            }
        });
        list.setOnScroll(e -> follow = false);
        setCenter(list);
    }

    private void reset() {
        rows.clear();
        scannedTo = 0;
        follow = true;
    }

    /**
     * @param limit read records up to this sequence number (the playhead in review, the head live)
     */
    void refresh(Timeline current, long limit, WorldView names) {
        if (current != timeline) {
            timeline = current;
            reset();
        }
        if (limit < scannedTo) {
            // The playhead moved backwards: rebuild from the start of the visible window.
            reset();
        }
        EventLog log = timeline.log();
        long start = Math.max(scannedTo, limit - MAX_PER_FRAME);
        String filter = search.getText() == null ? "" : search.getText().strip().toLowerCase(Locale.ROOT);
        List<Row> added = new ArrayList<>();
        for (long s = start; s < limit; s++) {
            EventRecord r = log.get(s);
            if (r.event().internal() && !debug) {
                continue;
            }
            EventText.Group group = EventText.group(r.event());
            if (!groups.contains(group) || (hideRoutine.isSelected() && EventText.routine(r.event()))) {
                continue;
            }
            String text = EventText.describe(r, names);
            if (!filter.isEmpty() && !text.toLowerCase(Locale.ROOT).contains(filter)) {
                continue;
            }
            added.add(new Row(r, FacilityClock.format(r.tick()), text, group));
        }
        scannedTo = limit;
        if (added.isEmpty()) {
            return;
        }
        rows.addAll(added);
        if (rows.size() > MAX_ROWS) {
            rows.remove(0, rows.size() - MAX_ROWS);
        }
        if (follow) {
            list.scrollTo(rows.size() - 1);
        }
    }

    void followLatest() {
        follow = true;
        if (!rows.isEmpty()) {
            list.scrollTo(rows.size() - 1);
        }
    }

    private static final class Cell extends ListCell<Row> {
        private final Label time = Widgets.label("", "memory-time");
        private final Label group = Widgets.label("", "memory-time");
        private final Label text = Widgets.label("", "kv-value");
        private final HBox box = new HBox(10, time, group, text);

        Cell() {
            group.setMinWidth(66);
            time.setMinWidth(60);
            box.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(Row r, boolean empty) {
            super.updateItem(r, empty);
            if (empty || r == null) {
                setGraphic(null);
                return;
            }
            time.setText(r.time());
            group.setText(r.group().name().toLowerCase(Locale.ROOT));
            text.setText(r.text());
            text.setTextFill(Palette.event(r.record().event()));
            setGraphic(box);
        }
    }
}
