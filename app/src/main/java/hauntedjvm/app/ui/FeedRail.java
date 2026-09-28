package hauntedjvm.app.ui;

import hauntedjvm.app.render.CameraRenderer;
import hauntedjvm.app.render.FloorplanRenderer;
import hauntedjvm.app.render.Palette;
import hauntedjvm.app.render.PostFx;
import hauntedjvm.app.render.RenderInput;
import hauntedjvm.app.render.SystemRenderer;
import hauntedjvm.core.entity.CameraFacet;
import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityId;
import hauntedjvm.core.entity.EntityKind;
import hauntedjvm.core.entity.EntityState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

/**
 * Left rail: every feed with a live thumbnail, then the night-shift roster.
 *
 * <p>Thumbnails are refreshed round-robin, two per frame, so eight cameras cost about as much
 * as one full-size render.
 */
final class FeedRail extends ScrollPane {

    private static final double THUMB_W = 176;
    private static final double THUMB_H = 99;

    private final Consumer<Feed> choose;
    private final Consumer<Selection> select;
    private final CameraRenderer cameraRenderer;
    private final FloorplanRenderer floorplanRenderer;
    private final SystemRenderer systemRenderer;
    private final PostFx fx;
    private final VBox tiles = new VBox(2);
    private final VBox roster = new VBox(0);
    private final Map<Feed, Tile> tileByFeed = new LinkedHashMap<>();
    private final Map<EntityId, RosterRow> rosterRows = new LinkedHashMap<>();
    private int nextThumb;

    private final class Tile extends VBox {
        final Feed feed;
        final Canvas canvas = new Canvas(THUMB_W, THUMB_H);
        final Label code = Widgets.label("", "feed-code");
        final Label room = Widgets.label("", "feed-room");
        final Circle dot = new Circle(3.5, Palette.PHOSPHOR);

        Tile(Feed feed) {
            this.feed = feed;
            getStyleClass().add("feed-tile");
            code.setText(feed.label());
            HBox line = new HBox(6, dot, code, Widgets.spacer(), room);
            line.setAlignment(Pos.CENTER_LEFT);
            getChildren().addAll(canvas, line);
            setOnMouseClicked(e -> choose.accept(feed));
        }
    }

    FeedRail(Consumer<Feed> choose, Consumer<Selection> select, CameraRenderer cameraRenderer,
             FloorplanRenderer floorplanRenderer, SystemRenderer systemRenderer, PostFx fx) {
        this.choose = choose;
        this.select = select;
        this.cameraRenderer = cameraRenderer;
        this.floorplanRenderer = floorplanRenderer;
        this.systemRenderer = systemRenderer;
        this.fx = fx;
        VBox content = new VBox(2, Widgets.sectionTitle("FEEDS", "real", null), tiles,
                Widgets.sectionTitle("NIGHT SHIFT", "real", null), roster);
        content.setPadding(new Insets(0, 4, 12, 4));
        setContent(content);
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        setMinWidth(THUMB_W + 28);
        setPrefWidth(THUMB_W + 28);
    }

    /** Structural refresh: which feeds exist, who is on the roster, what is selected. */
    void refresh(Display d, Feed current, Selection selection) {
        List<Feed> feeds = new ArrayList<>();
        feeds.add(new Feed.Floorplan());
        for (Entity c : d.live().ofKind(EntityKind.CAMERA)) {
            feeds.add(new Feed.Camera(c.name()));
        }
        feeds.add(new Feed.SystemTable());
        if (!feeds.equals(new ArrayList<>(tileByFeed.keySet()))) {
            tileByFeed.clear();
            tiles.getChildren().clear();
            for (Feed f : feeds) {
                Tile t = new Tile(f);
                tileByFeed.put(f, t);
                tiles.getChildren().add(t);
            }
        }
        for (Tile t : tileByFeed.values()) {
            t.getStyleClass().remove("selected");
            if (t.feed.equals(current)) {
                t.getStyleClass().add("selected");
            }
            if (t.feed instanceof Feed.Camera cam && d.live().camera(cam.code()) != null) {
                CameraFacet cf = (CameraFacet) d.live().camera(cam.code()).facet();
                t.room.setText(cf.roomCode());
                t.dot.setFill(switch (cf.status()) {
                    case ONLINE -> Palette.PHOSPHOR;
                    case INTERFERENCE -> Palette.AMBER;
                    case LOOPING -> Palette.TEAL;
                    case OFFLINE -> Palette.RED;
                });
            } else if (t.feed instanceof Feed.Floorplan) {
                t.room.setText(d.live().trackerOnline() ? "TRACKER" : "TRACKER DOWN");
                t.dot.setFill(d.live().trackerOnline() ? Palette.PHOSPHOR : Palette.RED);
            } else {
                t.room.setText("PROCESSES");
            }
        }
        roster(d, selection);
    }

    /** A roster line; built once per person, updated in place. */
    private final class RosterRow extends HBox {
        final Circle dot = new Circle(3.5);
        final Label name = Widgets.label("", "kv-value");
        final Label where = Widgets.label("", "dim");

        RosterRow(EntityId id) {
            super(6);
            getStyleClass().add("roster-row");
            setAlignment(Pos.CENTER_LEFT);
            where.setStyle("-fx-font-size: 10px;");
            getChildren().addAll(dot, name, Widgets.spacer(), where);
            setOnMouseClicked(e -> select.accept(new Selection.OfEntity(id)));
        }
    }

    private void roster(Display d, Selection selection) {
        List<Entity> persons = d.world().ofKind(EntityKind.PERSON);
        if (rosterRows.size() != persons.size()) {
            roster.getChildren().clear();
            rosterRows.clear();
            for (Entity p : persons) {
                RosterRow row = new RosterRow(p.id());
                rosterRows.put(p.id(), row);
                roster.getChildren().add(row);
            }
        }
        EntityId selected = selection instanceof Selection.OfEntity se ? se.id() : null;
        for (Entity p : persons) {
            RosterRow row = rosterRows.get(p.id());
            if (row == null) {
                continue;
            }
            row.dot.setFill(Palette.state(p.state()));
            String name = p.id() + " " + p.name();
            if (!row.name.getText().equals(name)) {
                row.name.setText(name);
            }
            String room = p.state() == EntityState.MISSING ? "missing"
                    : p.reportedPosition() == null ? "?" : d.world().map().roomCodeAt(p.reportedPosition());
            String where = room == null ? "doorway" : room;
            if (!row.where.getText().equals(where)) {
                row.where.setText(where);
            }
            boolean isSelected = p.id().equals(selected);
            if (isSelected != row.getStyleClass().contains("selected")) {
                if (isSelected) {
                    row.getStyleClass().add("selected");
                } else {
                    row.getStyleClass().remove("selected");
                }
            }
        }
    }

    /** Redraws a couple of thumbnails; called every frame. */
    void drawThumbnails(Display d, RenderInput in) {
        if (tileByFeed.isEmpty()) {
            return;
        }
        List<Tile> all = new ArrayList<>(tileByFeed.values());
        for (int i = 0; i < 2; i++) {
            Tile t = all.get(nextThumb++ % all.size());
            var g = t.canvas.getGraphicsContext2D();
            switch (t.feed) {
                case Feed.Camera cam -> {
                    Entity camera = d.world().camera(cam.code());
                    if (camera == null) {
                        camera = d.live().camera(cam.code());
                    }
                    if (camera != null && d.world().camera(cam.code()) != null) {
                        cameraRenderer.render(g, THUMB_W, THUMB_H, camera, d.world(), d.world(), in, fx, true);
                    } else {
                        g.setFill(Palette.BACKGROUND);
                        g.fillRect(0, 0, THUMB_W, THUMB_H);
                    }
                }
                case Feed.Floorplan f -> floorplanRenderer.render(g, THUMB_W, THUMB_H, in, fx);
                case Feed.SystemTable s -> systemRenderer.render(g, THUMB_W, THUMB_H, in, fx);
            }
        }
    }
}
