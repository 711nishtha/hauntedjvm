package hauntedjvm.app.render;

import hauntedjvm.core.behavior.Perception;
import hauntedjvm.core.entity.CameraFacet;
import hauntedjvm.core.entity.CameraStatus;
import hauntedjvm.core.entity.DoorFacet;
import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityId;
import hauntedjvm.core.entity.EntityKind;
import hauntedjvm.core.entity.EntityState;
import hauntedjvm.core.entity.LightMode;
import hauntedjvm.core.entity.Relationship;
import hauntedjvm.core.entity.RoomFacet;
import hauntedjvm.core.incident.Directive;
import hauntedjvm.core.state.WorldView;
import hauntedjvm.core.world.Cell;
import hauntedjvm.core.world.FacilityMap;
import hauntedjvm.core.world.RoomKind;
import hauntedjvm.core.world.RoomLayout;
import java.util.ArrayList;
import java.util.List;
import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

/**
 * The badge tracker's view of the facility.
 *
 * <p>This screen shows where badges <em>report</em> people to be, not where they are. Most of the
 * time that is the same thing. When it is not (a pinned badge, a frozen tracker, a badge whose
 * wearer is missing, a badge worn by something else) the only way to notice is to compare with
 * a camera. The unaccounted entity wears no badge, so it never appears here at all, unless it
 * has taken one.
 */
public final class FloorplanRenderer {

    private final Font label;
    private final Font small;
    private List<double[]> walls;
    private List<double[]> hiddenWalls;
    private FacilityMap cachedFor;

    public FloorplanRenderer(Font label, Font small) {
        this.label = label;
        this.small = small;
    }

    /** Screen transform for a map fitted into a canvas. */
    public record Fit(double scale, double ox, double oy) {
        double x(double cellX) {
            return ox + cellX * scale;
        }

        double y(double cellY) {
            return oy + cellY * scale;
        }

        public double[] toCell(double sx, double sy) {
            return new double[] {(sx - ox) / scale, (sy - oy) / scale};
        }
    }

    public static Fit fit(FacilityMap map, double w, double h) {
        double margin = w < 320 ? 6 : 28;
        double header = w < 320 ? 0 : 16;
        double scale = Math.min((w - margin * 2) / map.width(), (h - margin * 2 - header) / map.height());
        double ox = (w - map.width() * scale) / 2;
        double oy = (h - map.height() * scale) / 2 + header / 2;
        return new Fit(scale, ox, oy);
    }

    public List<Hit> render(GraphicsContext g, double w, double h, RenderInput in, PostFx fx) {
        WorldView world = in.world();
        FacilityMap map = world.map();
        prepare(map);
        Fit f = fit(map, w, h);
        double s = f.scale();
        g.setFill(Palette.BACKGROUND);
        g.fillRect(0, 0, w, h);
        dots(g, w, h, s);

        String observed = world.observedRoom();
        for (Entity roomEntity : world.ofKind(EntityKind.ROOM)) {
            RoomFacet rf = (RoomFacet) roomEntity.facet();
            if (!rf.listed()) {
                continue;
            }
            RoomLayout room = map.room(rf.code());
            fillRoom(g, f, room, rf, world, rf.code().equals(observed));
        }
        boolean compact = w < 320;
        drawSegments(g, f, walls, Palette.PHOSPHOR_DIM, s);
        revealed(g, f, world, map, s);
        doors(g, f, world, map, s);
        terminals(g, f, map, s);
        if (!compact) {
            roomLabels(g, f, world, map, observed, s);
        }
        directive(g, f, world, map, in.nanos());
        cameras(g, f, world, map, s);
        objects(g, f, world, s);
        traces(g, f, world, in);
        List<Hit> hits = badges(g, f, world, in, s);
        if (in.debug()) {
            truePositions(g, f, world, s);
        }
        if (!compact) {
            header(g, w, world);
            legend(g, h);
        }
        fx.grain(g, w, h, 0.05);
        fx.scanlines(g, w, h, 0.18);
        fx.vignette(g, w, h, 0.6);
        return hits;
    }

    private void prepare(FacilityMap map) {
        if (cachedFor == map) {
            return;
        }
        walls = new ArrayList<>();
        hiddenWalls = new ArrayList<>();
        int[][] dirs = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                Cell c = new Cell(x, y);
                RoomLayout room = map.roomAt(c);
                boolean hidden = room != null && room.kind() == RoomKind.HIDDEN;
                if (!(map.tile(c).walkable() || hidden)) {
                    continue;
                }
                for (int[] d : dirs) {
                    Cell n = c.offset(d[0], d[1]);
                    RoomLayout other = map.roomAt(n);
                    boolean otherHidden = other != null && other.kind() == RoomKind.HIDDEN;
                    // A sealed door is drawn as wall: from the corridor there is nothing there.
                    boolean solid = !map.tile(n).walkable();
                    if (hidden) {
                        if (solid || !otherHidden) {
                            hiddenWalls.add(segment(c, d));
                        }
                    } else if (solid || otherHidden) {
                        walls.add(segment(c, d));
                    }
                }
            }
        }
        cachedFor = map;
    }

    private static double[] segment(Cell c, int[] d) {
        if (d[1] == -1) {
            return new double[] {c.x(), c.y(), c.x() + 1, c.y()};
        }
        if (d[1] == 1) {
            return new double[] {c.x(), c.y() + 1, c.x() + 1, c.y() + 1};
        }
        if (d[0] == 1) {
            return new double[] {c.x() + 1, c.y(), c.x() + 1, c.y() + 1};
        }
        return new double[] {c.x(), c.y(), c.x(), c.y() + 1};
    }

    private void dots(GraphicsContext g, double w, double h, double s) {
        g.setFill(Palette.GRID);
        double step = Math.max(12, s * 2);
        for (double y = step / 2; y < h; y += step) {
            for (double x = step / 2; x < w; x += step) {
                g.fillRect(x, y, 1, 1);
            }
        }
    }

    private void fillRoom(GraphicsContext g, Fit f, RoomLayout room, RoomFacet rf, WorldView world, boolean observed) {
        double light = Perception.light(world, room.code(), world.tick());
        Color base = rf.light() == LightMode.EMERGENCY ? Palette.RED : Palette.PHOSPHOR;
        double alpha = 0.012 + 0.05 * light + (observed ? 0.04 : 0);
        g.setFill(base.deriveColor(0, 1, 1, alpha));
        g.fillRect(f.x(room.minX()), f.y(room.minY()), room.width() * f.scale(), room.height() * f.scale());
    }

    private void drawSegments(GraphicsContext g, Fit f, List<double[]> segments, Color color, double s) {
        g.setLineCap(javafx.scene.shape.StrokeLineCap.SQUARE);
        g.setStroke(color.deriveColor(0, 1, 1, 0.18));
        g.setLineWidth(Math.max(3, s * 0.35));
        for (double[] seg : segments) {
            g.strokeLine(f.x(seg[0]), f.y(seg[1]), f.x(seg[2]), f.y(seg[3]));
        }
        g.setStroke(color);
        g.setLineWidth(Math.max(1, s * 0.1));
        for (double[] seg : segments) {
            g.strokeLine(f.x(seg[0]), f.y(seg[1]), f.x(seg[2]), f.y(seg[3]));
        }
    }

    private void revealed(GraphicsContext g, Fit f, WorldView world, FacilityMap map, double s) {
        for (RoomLayout room : map.rooms()) {
            if (room.kind() != RoomKind.HIDDEN) {
                continue;
            }
            Entity e = world.room(room.code());
            if (e == null || !((RoomFacet) e.facet()).listed()) {
                continue;
            }
            g.setFill(Palette.RED.deriveColor(0, 1, 1, 0.07));
            g.fillRect(f.x(room.minX()), f.y(room.minY()), room.width() * s, room.height() * s);
            g.setLineDashes(s * 0.4, s * 0.3);
            drawSegments(g, f, hiddenWalls, Palette.RED, s);
            g.setLineDashes((double[]) null);
        }
    }

    private void doors(GraphicsContext g, Fit f, WorldView world, FacilityMap map, double s) {
        for (Entity e : world.ofKind(EntityKind.DEVICE)) {
            if (!(e.facet() instanceof DoorFacet d) || d.sealed()) {
                continue;
            }
            Cell c = e.position();
            boolean vertical = map.roomAt(c.offset(0, -1)) != null && map.roomAt(c.offset(0, 1)) != null;
            double x = f.x(c.x());
            double y = f.y(c.y());
            g.setFill(Palette.BACKGROUND);
            g.fillRect(x, y, s, s);
            Color color = d.locked() ? Palette.RED : d.open() ? Palette.PHOSPHOR : Palette.AMBER_DIM;
            g.setStroke(color);
            g.setLineWidth(Math.max(1.5, s * 0.16));
            if (d.open()) {
                // Two jamb marks with the gap between them.
                if (vertical) {
                    g.strokeLine(x, y + s / 2, x + s * 0.2, y + s / 2);
                    g.strokeLine(x + s * 0.8, y + s / 2, x + s, y + s / 2);
                } else {
                    g.strokeLine(x + s / 2, y, x + s / 2, y + s * 0.2);
                    g.strokeLine(x + s / 2, y + s * 0.8, x + s / 2, y + s);
                }
            } else if (vertical) {
                g.strokeLine(x, y + s / 2, x + s, y + s / 2);
            } else {
                g.strokeLine(x + s / 2, y, x + s / 2, y + s);
            }
            if (d.locked()) {
                g.setFill(Palette.RED);
                g.fillRect(x + s * 0.35, y + s * 0.35, s * 0.3, s * 0.3);
            }
        }
    }

    private void terminals(GraphicsContext g, Fit f, FacilityMap map, double s) {
        g.setFill(Palette.PHOSPHOR_DIM);
        for (RoomLayout r : map.rooms()) {
            if (r.kind() == RoomKind.HIDDEN) {
                continue;
            }
            for (Cell t : r.terminals()) {
                g.fillRect(f.x(t.x()) + s * 0.25, f.y(t.y()) + s * 0.3, s * 0.5, s * 0.4);
            }
        }
    }

    private void roomLabels(GraphicsContext g, Fit f, WorldView world, FacilityMap map, String observed, double s) {
        g.setTextAlign(TextAlignment.LEFT);
        g.setTextBaseline(VPos.TOP);
        for (Entity e : world.ofKind(EntityKind.ROOM)) {
            RoomFacet rf = (RoomFacet) e.facet();
            if (!rf.listed()) {
                continue;
            }
            RoomLayout room = map.room(rf.code());
            boolean hidden = room.kind() == RoomKind.HIDDEN;
            double x = f.x(room.minX()) + 3;
            double y = f.y(room.minY()) + 2;
            g.setFont(small);
            g.setFill(hidden ? Palette.RED : rf.code().equals(observed) ? Palette.PHOSPHOR : Palette.MUTED);
            g.fillText(rf.code(), x, y);
            if (s >= 11 && room.height() >= 3) {
                g.setFill(hidden ? Palette.RED.deriveColor(0, 1, 0.7, 1) : Palette.MUTED.deriveColor(0, 1, 0.7, 1));
                g.fillText(hidden ? "NOT ON PLAN" : rf.label(), x, y + small.getSize() + 1);
            }
        }
    }

    private void directive(GraphicsContext g, Fit f, WorldView world, FacilityMap map, long nanos) {
        Directive d = world.incident().directive();
        if (d == null || !d.activeAt(world.tick()) || d.type() != Directive.Type.GATHER) {
            return;
        }
        RoomLayout room = map.room(d.roomCode());
        double pulse = 0.4 + 0.4 * Math.sin(nanos / 2.5e8);
        g.setStroke(Palette.TEAL.deriveColor(0, 1, 1, pulse));
        g.setLineWidth(2);
        g.setLineDashes(6, 4);
        g.strokeRect(f.x(room.minX()) - 2, f.y(room.minY()) - 2, room.width() * f.scale() + 4,
                room.height() * f.scale() + 4);
        g.setLineDashes((double[]) null);
    }

    private void cameras(GraphicsContext g, Fit f, WorldView world, FacilityMap map, double s) {
        String observed = world.incident().observedCamera();
        for (Entity e : world.ofKind(EntityKind.CAMERA)) {
            CameraFacet cf = (CameraFacet) e.facet();
            RoomLayout room = map.room(cf.roomCode());
            double cx = f.x(cf.mount().x() + 0.5);
            double cy = f.y(cf.mount().y() + 0.5);
            double tx = f.x(room.centre().x() + 0.5);
            double ty = f.y(room.centre().y() + 0.5);
            double angle = Math.toDegrees(Math.atan2(-(ty - cy), tx - cx));
            boolean watched = cf.code().equals(observed);
            Color color = switch (cf.status()) {
                case ONLINE -> watched ? Palette.PHOSPHOR : Palette.PHOSPHOR_DIM;
                case INTERFERENCE -> Palette.AMBER;
                case LOOPING -> Palette.TEAL;
                case OFFLINE -> Palette.RED;
            };
            double reach = Math.min(Math.hypot(tx - cx, ty - cy) * 1.4, s * 9);
            g.setFill(color.deriveColor(0, 1, 1, watched ? 0.14 : 0.05));
            g.fillArc(cx - reach, cy - reach, reach * 2, reach * 2, angle - 38, 76, ArcType.ROUND);
            g.setFill(color);
            double r = Math.max(3, s * 0.35);
            g.fillOval(cx - r / 2, cy - r / 2, r, r);
            if (watched || cf.status() != CameraStatus.ONLINE) {
                g.setFont(small);
                g.setTextAlign(TextAlignment.LEFT);
                g.setTextBaseline(VPos.CENTER);
                g.fillText(cf.code(), cx + r, cy + r * 1.5);
            }
        }
    }

    private void objects(GraphicsContext g, Fit f, WorldView world, double s) {
        for (Entity o : world.ofKind(EntityKind.OBJECT)) {
            if (o.position() == null) {
                continue;
            }
            double x = f.x(o.position().x() + 0.5);
            double y = f.y(o.position().y() + 0.5);
            double r = Math.max(2, s * 0.22);
            g.setStroke(Palette.MUTED);
            g.setLineWidth(1);
            g.strokePolygon(new double[] {x, x + r, x, x - r}, new double[] {y - r, y, y + r, y}, 4);
        }
    }

    private void traces(GraphicsContext g, Fit f, WorldView world, RenderInput in) {
        for (EntityId id : in.traced()) {
            Entity e = world.entity(id);
            if (e == null || !e.hasMind() || e.reportedPosition() == null) {
                continue;
            }
            double[] a = in.motion().reported(e.id(), e.reportedPosition(), in.alpha());
            for (Relationship r : e.mind().relationships()) {
                Entity other = world.entity(r.other());
                if (other == null || other.reportedPosition() == null) {
                    continue;
                }
                double[] b = in.motion().reported(other.id(), other.reportedPosition(), in.alpha());
                g.setStroke(Palette.TEAL.deriveColor(0, 1, 1, 0.25 + 0.6 * r.trust()));
                g.setLineWidth(0.5 + r.familiarity() * 2.5);
                g.strokeLine(f.x(a[0]), f.y(a[1]), f.x(b[0]), f.y(b[1]));
            }
        }
    }

    private List<Hit> badges(GraphicsContext g, Fit f, WorldView world, RenderInput in, double s) {
        List<Hit> hits = new ArrayList<>();
        double r = Math.max(4, s * 0.36);
        for (Entity e : world.entities()) {
            if (!e.tracked() || e.reportedPosition() == null || !e.kind().mobile()) {
                continue;
            }
            double[] pos = in.motion().reported(e.id(), e.reportedPosition(), in.alpha());
            double x = f.x(pos[0]);
            double y = f.y(pos[1]);
            // The badge reports whoever it belongs to, not whoever is wearing it.
            Entity claimed = world.entity(e.identity());
            Entity shown = claimed == null ? e : claimed;
            boolean missing = e.state() == EntityState.MISSING;
            Color color = missing ? Palette.MUTED : Palette.state(e.kind() == EntityKind.UNKNOWN ? EntityState.NORMAL
                    : e.state());
            if (missing) {
                double pulse = 1 + 0.6 * ((in.nanos() / 1e9) % 1.5) / 1.5;
                g.setStroke(color.deriveColor(0, 1, 1, 0.6));
                g.setLineWidth(1);
                g.strokeOval(x - r * pulse, y - r * pulse, r * 2 * pulse, r * 2 * pulse);
                g.strokeOval(x - r, y - r, r * 2, r * 2);
            } else {
                g.setFill(color.deriveColor(0, 1, 1, 0.22));
                g.fillOval(x - r * 1.7, y - r * 1.7, r * 3.4, r * 3.4);
                g.setFill(color);
                g.fillOval(x - r, y - r, r * 2, r * 2);
            }
            boolean selected = e.id().equals(in.selected());
            if (selected) {
                g.setStroke(Palette.PAPER);
                g.setLineWidth(1.5);
                g.strokeOval(x - r * 2.2, y - r * 2.2, r * 4.4, r * 4.4);
            }
            if (s >= 9 || selected) {
                g.setFont(small);
                g.setTextAlign(TextAlignment.LEFT);
                g.setTextBaseline(VPos.CENTER);
                g.setFill(selected ? Palette.PAPER : color.deriveColor(0, 1, 0.85, 1));
                g.fillText(shown.name() + (missing ? " ?" : ""), x + r + 3, y);
            }
            hits.add(new Hit(e.id(), x - r * 2, y - r * 2, r * 4, r * 4));
        }
        return hits;
    }

    private void truePositions(GraphicsContext g, Fit f, WorldView world, double s) {
        for (Entity e : world.entities()) {
            if (!e.kind().mobile() || e.position() == null) {
                continue;
            }
            double x = f.x(e.position().x() + 0.5);
            double y = f.y(e.position().y() + 0.5);
            g.setStroke(e.kind() == EntityKind.UNKNOWN ? Palette.RED : Palette.LILAC);
            g.setLineWidth(1);
            g.strokeRect(x - s * 0.3, y - s * 0.3, s * 0.6, s * 0.6);
        }
    }

    private void header(GraphicsContext g, double w, WorldView world) {
        g.setFont(label);
        g.setTextAlign(TextAlignment.LEFT);
        g.setTextBaseline(VPos.TOP);
        g.setFill(Palette.PHOSPHOR);
        g.fillText("BADGE TRACKER // " + world.map().name(), 14, 8);
        g.setFont(small);
        if (!world.trackerOnline()) {
            g.setFill(Palette.RED);
            g.fillText("badge-trackd NOT RUNNING — POSITIONS FROZEN", 14, 8 + label.getSize() + 2);
        } else {
            g.setFill(Palette.MUTED);
            g.fillText("positions as reported by badges; the unbadged do not appear", 14, 8 + label.getSize() + 2);
        }
    }

    private void legend(GraphicsContext g, double h) {
        EntityState[] shown = {EntityState.NORMAL, EntityState.SUSPICIOUS, EntityState.AFRAID, EntityState.AVOIDING,
            EntityState.INVESTIGATING, EntityState.FOLLOWING, EntityState.ECHOING, EntityState.CORRUPTED, EntityState.AWARE,
            EntityState.MISSING};
        g.setFont(small);
        g.setTextAlign(TextAlignment.LEFT);
        g.setTextBaseline(VPos.CENTER);
        double x = 14;
        double y = h - 12;
        for (EntityState st : shown) {
            g.setFill(Palette.state(st));
            g.fillOval(x, y - 3, 6, 6);
            g.setFill(Palette.MUTED);
            String text = st.name().toLowerCase();
            g.fillText(text, x + 9, y);
            x += 9 + text.length() * small.getSize() * 0.62 + 10;
        }
    }
}
