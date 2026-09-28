package hauntedjvm.app.ui;

import hauntedjvm.app.session.Session;
import hauntedjvm.core.time.FacilityClock;
import hauntedjvm.persistence.InvestigationNote;
import java.util.List;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/** The operator's notebook. Notes are saved with the session and printed in the incident report. */
final class NotesPanel extends BorderPane {

    private final Session session;
    private final Operator operator;
    private final VBox list = new VBox(6);
    private final TextArea draft = new TextArea();
    private final Label target = Widgets.label("", "dim");
    private int shown = -1;
    private Selection currentSelection = new Selection.None();
    private long currentTick;

    NotesPanel(Session session, Operator operator) {
        this.session = session;
        this.operator = operator;
        draft.setPromptText("What did you see? (Ctrl+Enter to save)");
        draft.setPrefRowCount(3);
        draft.setWrapText(true);
        draft.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && e.isControlDown()) {
                save();
                e.consume();
            }
        });
        HBox actions = new HBox(8, target, Widgets.spacer(), Widgets.button("SAVE NOTE", this::save));
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox editor = new VBox(6, draft, actions);
        editor.setPadding(new Insets(10));
        setTop(editor);
        list.setPadding(new Insets(4, 10, 12, 10));
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        setCenter(scroll);
    }

    void focusDraft(String ref) {
        draft.setUserData(ref);
        target.setText("attaching to " + ref);
        draft.requestFocus();
    }

    void refresh(Display d, Selection selection) {
        currentSelection = selection;
        currentTick = d.tick();
        if (draft.getUserData() == null) {
            target.setText("attaching to " + selection.ref());
        }
        List<InvestigationNote> notes = session.notes();
        if (notes.size() == shown) {
            return;
        }
        shown = notes.size();
        list.getChildren().clear();
        if (notes.isEmpty()) {
            list.getChildren().add(Widgets.label("No notes yet. Anything you write here is saved with the session.", "dim"));
        }
        for (InvestigationNote n : notes.reversed()) {
            Label head = Widgets.label(FacilityClock.format(n.tick()) + "  " + n.targetRef(), "memory-time", "link");
            head.setOnMouseClicked(e -> open(n.targetRef(), n.tick()));
            Label body = Widgets.label(n.text(), "memory-note");
            body.setWrapText(true);
            Label delete = Widgets.label("remove", "dim", "link");
            delete.setOnMouseClicked(e -> {
                session.removeNote(n.id());
                shown = -1;
            });
            VBox card = new VBox(2, new HBox(8, head, Widgets.spacer(), delete), body);
            card.getStyleClass().add("memory-row");
            list.getChildren().add(card);
        }
    }

    private void open(String ref, long tick) {
        Selection s = parse(ref);
        if (s != null) {
            operator.select(s);
        }
        operator.jumpTo(tick, -1);
    }

    private static Selection parse(String ref) {
        try {
            if (ref.startsWith("entity:#")) {
                return new Selection.OfEntity(hauntedjvm.core.entity.EntityId.of(Integer.parseInt(ref.substring(8))));
            }
            if (ref.startsWith("event:")) {
                return new Selection.OfEvent(Long.parseLong(ref.substring(6)));
            }
            if (ref.startsWith("anomaly:")) {
                return new Selection.OfAnomaly(ref.substring(8));
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    private void save() {
        String text = draft.getText();
        if (text == null || text.isBlank()) {
            return;
        }
        String ref = draft.getUserData() instanceof String s ? s : currentSelection.ref();
        session.addNote(InvestigationNote.write(ref, currentTick, text));
        draft.clear();
        draft.setUserData(null);
        shown = -1;
    }
}
