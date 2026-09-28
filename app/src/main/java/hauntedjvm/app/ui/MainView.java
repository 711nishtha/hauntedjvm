package hauntedjvm.app.ui;

import hauntedjvm.app.render.CameraRenderer;
import hauntedjvm.app.render.FloorplanRenderer;
import hauntedjvm.app.render.Hit;
import hauntedjvm.app.render.Palette;
import hauntedjvm.app.render.PostFx;
import hauntedjvm.app.render.RenderInput;
import hauntedjvm.app.render.SystemRenderer;
import hauntedjvm.core.entity.CameraFacet;
import hauntedjvm.core.entity.CameraStatus;
import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.state.WorldView;
import hauntedjvm.core.timeline.ReplayCursor;
import hauntedjvm.core.timeline.Timeline;
import hauntedjvm.core.world.Cell;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javafx.geometry.VPos;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.text.TextAlignment;

/**
 * The main monitor. Renders the selected feed every frame and turns clicks into selections.
 *
 * <p>A looping camera is rendered from a {@link ReplayCursor} positioned in the past: the same
 * replay machinery the operator uses to review the recording is what the facility uses to show
 * them the wrong time.
 */
final class MainView extends Pane {

    private final Canvas canvas = new Canvas();
    private final CameraRenderer cameras;
    private final FloorplanRenderer floorplan;
    private final SystemRenderer system;
    private final PostFx fx;
    private final Operator operator;
    private final Map<String, ReplayCursor> loops = new HashMap<>();
    private Timeline loopTimeline;
    private List<Hit> hits = List.of();
    private Display last;
    private Feed lastFeed;

    MainView(Operator operator, CameraRenderer cameras, FloorplanRenderer floorplan, SystemRenderer system, PostFx fx) {
        this.operator = operator;
        this.cameras = cameras;
        this.floorplan = floorplan;
        this.system = system;
        this.fx = fx;
        getChildren().add(canvas);
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());
        setMinSize(320, 200);
        canvas.setOnMouseClicked(e -> click(e.getX(), e.getY()));
        canvas.setOnMouseMoved(e -> canvas.setCursor(hitAt(e.getX(), e.getY()) != null ? Cursor.HAND : Cursor.DEFAULT));
    }

    void render(Display d, Feed feed, RenderInput in) {
        last = d;
        lastFeed = feed;
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w < 1 || h < 1) {
            return;
        }
        hits = switch (feed) {
            case Feed.Floorplan f -> floorplan.render(g, w, h, in, fx);
            case Feed.SystemTable s -> system.render(g, w, h, in, fx);
            case Feed.Camera c -> camera(g, w, h, c.code(), d, in);
        };
    }

    private List<Hit> camera(GraphicsContext g, double w, double h, String code, Display d, RenderInput in) {
        Entity camera = d.world().camera(code);
        if (camera == null) {
            g.setFill(Palette.BACKGROUND);
            g.fillRect(0, 0, w, h);
            fx.interference(g, w, h, 0.6);
            g.setFill(Palette.PAPER);
            g.setTextAlign(TextAlignment.CENTER);
            g.setTextBaseline(VPos.CENTER);
            g.setFont(Fonts.display(36));
            g.fillText(code + "  NOT INSTALLED AT THIS TIME", w / 2, h / 2);
            return List.of();
        }
        CameraFacet cf = (CameraFacet) camera.facet();
        WorldView feedWorld = d.world();
        if (cf.status() == CameraStatus.LOOPING && cf.timelineOffset() > 0) {
            Timeline tl = d.frame().timeline();
            if (tl != loopTimeline) {
                loops.clear();
                loopTimeline = tl;
            }
            ReplayCursor cursor = loops.computeIfAbsent(code, k -> new ReplayCursor(d.world().map(), tl));
            feedWorld = cursor.seek(d.tick() - cf.timelineOffset());
        }
        return cameras.render(g, w, h, camera, feedWorld, d.world(), in, fx, false);
    }

    private Hit hitAt(double x, double y) {
        for (int i = hits.size() - 1; i >= 0; i--) {
            if (hits.get(i).contains(x, y)) {
                return hits.get(i);
            }
        }
        return null;
    }

    private void click(double x, double y) {
        Hit hit = hitAt(x, y);
        if (hit != null) {
            operator.select(new Selection.OfEntity(hit.id()));
            return;
        }
        if (last == null) {
            return;
        }
        WorldView w = last.world();
        switch (lastFeed) {
            case Feed.Floorplan f -> {
                double[] cell = FloorplanRenderer.fit(w.map(), canvas.getWidth(), canvas.getHeight()).toCell(x, y);
                Cell c = new Cell((int) Math.floor(cell[0]), (int) Math.floor(cell[1]));
                Entity door = w.doorAt(c);
                String room = w.map().roomCodeAt(c);
                if (door != null) {
                    operator.select(new Selection.OfEntity(door.id()));
                } else if (room != null && w.room(room) != null) {
                    operator.select(new Selection.OfEntity(w.room(room).id()));
                }
            }
            case Feed.Camera c -> {
                Entity camera = w.camera(c.code());
                if (camera != null) {
                    Entity room = w.room(((CameraFacet) camera.facet()).roomCode());
                    operator.select(new Selection.OfEntity(room.id()));
                }
            }
            case Feed.SystemTable s -> operator.select(new Selection.None());
        }
    }
}
