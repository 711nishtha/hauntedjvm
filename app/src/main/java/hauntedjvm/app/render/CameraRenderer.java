package hauntedjvm.app.render;

import hauntedjvm.core.behavior.Perception;
import hauntedjvm.core.entity.CameraFacet;
import hauntedjvm.core.entity.CameraStatus;
import hauntedjvm.core.entity.DoorFacet;
import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityKind;
import hauntedjvm.core.entity.EntityState;
import hauntedjvm.core.entity.LightMode;
import hauntedjvm.core.state.WorldView;
import hauntedjvm.core.time.FacilityClock;
import hauntedjvm.core.world.Cell;
import hauntedjvm.core.world.FacilityMap;
import hauntedjvm.core.world.RoomLayout;
import hauntedjvm.core.world.Tile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

/**
 * Renders a surveillance feed: one room, seen from a camera in a ceiling corner.
 *
 * <p>Geometry is drawn back to front (painter's algorithm) from the static map: floor tiles,
 * then wall panels and doorways facing the lens, then furniture and people sorted by distance.
 * Rooms are rectangles, so everything inside a room is always in front of the walls it can see,
 * which is what makes plain painter's ordering correct here without a depth buffer.
 *
 * <p>Light comes from the simulation. Below a threshold the camera switches to infrared:
 * monochrome, noisy, lit only near the lens, with people glowing pale. The unaccounted entity
 * does not reflect infrared.
 */
public final class CameraRenderer {

    private static final double WALL_HEIGHT = 2.8;
    private static final double DOOR_HEIGHT = 2.1;
    private static final double CAMERA_HEIGHT = 2.6;
    private static final double FOV = 84;
    private static final double PERSON_HEIGHT = 1.74;
    private static final double UNKNOWN_HEIGHT = 2.06;

    private final Font osdLarge;
    private final Font osdSmall;

    public CameraRenderer(Font osdLarge, Font osdSmall) {
        this.osdLarge = osdLarge;
        this.osdSmall = osdSmall;
    }

    private record Look(boolean ir, double light, boolean emergency, double fogDistance) {
        Color shade(Color lit, Color infrared, double distance, double extra) {
            double fog = Math.exp(-distance / fogDistance);
            if (ir) {
                return infrared.deriveColor(0, 1, Math.clamp(fog * extra, 0.0, 1.0), 1);
            }
            Color c = lit.deriveColor(0, 1, Math.clamp((0.25 + 0.75 * light) * (0.45 + 0.55 * fog) * extra, 0.0, 1.2), 1);
            return emergency ? c.interpolate(Color.web("#3a0906"), 0.45) : c;
        }
    }

    private interface Drawable {
        double depth();

        void draw();
    }

    /**
     * Draws one feed.
     *
     * @param camera the camera entity (in {@code feed})
     * @param feed   the world the feed shows: the live world, or a reconstructed past while looping
     * @param live   the live world, for OSD details that do not loop
     * @return clickable regions for the people in view
     */
    public List<Hit> render(GraphicsContext g, double w, double h, Entity camera, WorldView feed, WorldView live,
                            RenderInput in, PostFx fx, boolean thumbnail) {
        CameraFacet cf = (CameraFacet) camera.facet();
        g.setFill(Color.BLACK);
        g.fillRect(0, 0, w, h);
        CameraStatus status = ((CameraFacet) live.camera(cf.code()).facet()).status();
        if (status == CameraStatus.OFFLINE) {
            noSignal(g, w, h, cf, in, fx);
            return List.of();
        }
        FacilityMap map = feed.map();
        RoomLayout room = map.room(cf.roomCode());
        double light = Perception.light(feed, room.code(), feed.tick());
        Look look = new Look(light < 0.35, light, feed.light(room.code()) == LightMode.EMERGENCY, light < 0.35 ? 7 : 16);
        Projection p = projection(cf, room, w, h);

        drawFloor(g, p, map, room, look);
        List<Drawable> drawables = new ArrayList<>();
        List<Hit> hits = new ArrayList<>();
        walls(g, p, feed, room, look, drawables);
        furniture(g, p, feed, room, look, drawables);
        figures(g, p, feed, live, room, look, in, drawables, hits, fx, thumbnail);
        drawables.sort(Comparator.comparingDouble(Drawable::depth).reversed());
        drawables.forEach(Drawable::draw);

        if (look.ir()) {
            // IR sensors read everything as the same greenish grey.
            g.save();
            g.setGlobalAlpha(0.08);
            g.setFill(Color.web("#9fd8a8"));
            g.fillRect(0, 0, w, h);
            g.restore();
        }
        boolean looping = status == CameraStatus.LOOPING;
        double interference = status == CameraStatus.INTERFERENCE ? 0.65 : looping ? 0.12 : 0;
        int level = live.incident().level();
        fx.grain(g, w, h, look.ir() ? 0.26 : 0.1 + level * 0.015);
        fx.interference(g, w, h, interference);
        fx.humBar(g, w, h, in.nanos(), thumbnail ? 0.02 : 0.035);
        fx.scanlines(g, w, h, thumbnail ? 0.2 : 0.28);
        fx.vignette(g, w, h, 0.85);
        fx.flicker(g, w, h, level >= 4 ? 0.08 : 0.015);
        if (!thumbnail) {
            osd(g, w, h, cf, room, feed, live, look, looping, in);
        }
        return hits;
    }

    private static Projection projection(CameraFacet cf, RoomLayout room, double w, double h) {
        Cell m = cf.mount();
        Cell c = room.centre();
        double cx = m.x() + (m.x() <= c.x() ? 0.12 : 0.88);
        double cy = m.y() + (m.y() <= c.y() ? 0.12 : 0.88);
        // Aim a little past the centre so the far wall sits in the upper third of the frame.
        double tx = c.x() + 0.5 + (c.x() + 0.5 - cx) * 0.35;
        double ty = c.y() + 0.5 + (c.y() + 0.5 - cy) * 0.35;
        return new Projection(cx, cy, CAMERA_HEIGHT, tx, ty, 0.1, FOV, w, h);
    }

    private void drawFloor(GraphicsContext g, Projection p, FacilityMap map, RoomLayout room, Look look) {
        List<Cell> cells = new ArrayList<>(room.cells());
        cells.sort(Comparator.comparingDouble((Cell c) -> p.distance(c.x() + 0.5, c.y() + 0.5, 0)).reversed());
        Color lit = Color.web("#3a3f39");
        Color ir = Color.web("#8fa592");
        for (Cell c : cells) {
            double[][] quad = p.polygon(new double[][] {
                {c.x(), c.y(), 0}, {c.x() + 1, c.y(), 0}, {c.x() + 1, c.y() + 1, 0}, {c.x(), c.y() + 1, 0}});
            if (quad == null) {
                continue;
            }
            double d = p.distance(c.x() + 0.5, c.y() + 0.5, 0);
            // A faint checker reads as floor tiles and gives the eye depth cues.
            double checker = ((c.x() + c.y()) & 1) == 0 ? 1.0 : 0.92;
            g.setFill(look.shade(lit, ir, d, checker * 0.8));
            g.fillPolygon(quad[0], quad[1], quad[0].length);
            g.setStroke(look.shade(Color.web("#1b1f1b"), Color.web("#3c4a3e"), d, 1));
            g.setLineWidth(0.6);
            g.strokePolygon(quad[0], quad[1], quad[0].length);
        }
    }

    private void walls(GraphicsContext g, Projection p, WorldView feed, RoomLayout room, Look look, List<Drawable> out) {
        FacilityMap map = feed.map();
        int[][] dirs = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        for (Cell c : room.cells()) {
            for (int[] d : dirs) {
                Cell n = c.offset(d[0], d[1]);
                if (map.roomAt(n) == room) {
                    continue;
                }
                double[] edge = edge(c, d);
                double x0 = edge[0];
                double y0 = edge[1];
                double x1 = edge[2];
                double y1 = edge[3];
                double nx = -d[0];
                double ny = -d[1];
                double mx = (x0 + x1) / 2;
                double my = (y0 + y1) / 2;
                if (!p.facing(mx, my, WALL_HEIGHT / 2, nx, ny, 0)) {
                    continue;
                }
                double depth = p.distance(mx, my, WALL_HEIGHT / 2);
                Tile tile = map.tile(n);
                Entity door = feed.doorAt(n);
                boolean doorway = tile == Tile.DOOR || (tile == Tile.SEALED_DOOR && door != null
                        && door.facet() instanceof DoorFacet df && !df.sealed());
                boolean opening = map.roomAt(n) != null;
                double brightness = d[1] != 0 ? 1.0 : 0.82;
                final double fx0 = x0;
                final double fy0 = y0;
                final double fx1 = x1;
                final double fy1 = y1;
                out.add(new Drawable() {
                    @Override
                    public double depth() {
                        // Walls always sit behind anything standing in the room.
                        return depth + 1000;
                    }

                    @Override
                    public void draw() {
                        if (opening) {
                            panel(g, p, fx0, fy0, fx1, fy1, 0, WALL_HEIGHT, Color.web("#050605"), look, depth, 1, false);
                        } else if (doorway) {
                            panel(g, p, fx0, fy0, fx1, fy1, DOOR_HEIGHT, WALL_HEIGHT, wallColor(), look, depth, brightness, true);
                            doorPanel(g, p, fx0, fy0, fx1, fy1, door, look, depth);
                        } else {
                            panel(g, p, fx0, fy0, fx1, fy1, 0, WALL_HEIGHT, wallColor(), look, depth, brightness, true);
                        }
                    }
                });
            }
        }
    }

    /** Endpoints of the boundary between cell {@code c} and its neighbour in direction {@code d}. */
    private static double[] edge(Cell c, int[] d) {
        if (d[1] == -1) {
            return new double[] {c.x(), c.y(), c.x() + 1, c.y()};
        }
        if (d[1] == 1) {
            return new double[] {c.x() + 1, c.y() + 1, c.x(), c.y() + 1};
        }
        if (d[0] == 1) {
            return new double[] {c.x() + 1, c.y(), c.x() + 1, c.y() + 1};
        }
        return new double[] {c.x(), c.y() + 1, c.x(), c.y()};
    }

    private static Color wallColor() {
        return Color.web("#4a5049");
    }

    private void panel(GraphicsContext g, Projection p, double x0, double y0, double x1, double y1, double z0, double z1,
                       Color color, Look look, double depth, double brightness, boolean detail) {
        double[][] quad = p.polygon(new double[][] {{x0, y0, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y0, z1}});
        if (quad == null) {
            return;
        }
        g.setFill(look.shade(color, Color.web("#a3b9a6"), depth, brightness * 0.8));
        g.fillPolygon(quad[0], quad[1], quad[0].length);
        if (!detail) {
            return;
        }
        g.setStroke(look.shade(Color.web("#262a25"), Color.web("#56665a"), depth, 1));
        g.setLineWidth(0.7);
        g.strokePolygon(quad[0], quad[1], quad[0].length);
        if (z0 == 0) {
            double[][] base = p.polygon(new double[][] {{x0, y0, 0}, {x1, y1, 0}, {x1, y1, 0.14}, {x0, y0, 0.14}});
            if (base != null) {
                g.setFill(look.shade(Color.web("#22261f"), Color.web("#4f5f52"), depth, 1));
                g.fillPolygon(base[0], base[1], base[0].length);
            }
        }
    }

    private void doorPanel(GraphicsContext g, Projection p, double x0, double y0, double x1, double y1, Entity door,
                           Look look, double depth) {
        boolean open = door != null && door.facet() instanceof DoorFacet df && df.open();
        boolean locked = door != null && door.facet() instanceof DoorFacet df && df.locked();
        if (open) {
            double[][] gap = p.polygon(new double[][] {{x0, y0, 0}, {x1, y1, 0}, {x1, y1, DOOR_HEIGHT}, {x0, y0, DOOR_HEIGHT}});
            if (gap != null) {
                g.setFill(Color.web("#020302"));
                g.fillPolygon(gap[0], gap[1], gap[0].length);
            }
            return;
        }
        double inset = 0.06;
        double ix0 = x0 + (x1 - x0) * inset;
        double iy0 = y0 + (y1 - y0) * inset;
        double ix1 = x1 - (x1 - x0) * inset;
        double iy1 = y1 - (y1 - y0) * inset;
        panel(g, p, x0, y0, x1, y1, 0, DOOR_HEIGHT, Color.web("#2b2f2a"), look, depth, 1, false);
        double[][] leaf = p.polygon(new double[][] {{ix0, iy0, 0}, {ix1, iy1, 0}, {ix1, iy1, DOOR_HEIGHT - 0.05},
            {ix0, iy0, DOOR_HEIGHT - 0.05}});
        if (leaf != null) {
            g.setFill(look.shade(Color.web("#3a3f37"), Color.web("#7f9483"), depth, 0.9));
            g.fillPolygon(leaf[0], leaf[1], leaf[0].length);
        }
        double wx0 = x0 + (x1 - x0) * 0.35;
        double wy0 = y0 + (y1 - y0) * 0.35;
        double wx1 = x0 + (x1 - x0) * 0.65;
        double wy1 = y0 + (y1 - y0) * 0.65;
        double[][] window = p.polygon(new double[][] {{wx0, wy0, 1.35}, {wx1, wy1, 1.35}, {wx1, wy1, 1.75}, {wx0, wy0, 1.75}});
        if (window != null) {
            g.setFill(Color.web("#050605"));
            g.fillPolygon(window[0], window[1], window[0].length);
        }
        double[] lamp = p.project(x0 + (x1 - x0) * 0.85, y0 + (y1 - y0) * 0.85, 1.05);
        if (lamp != null) {
            double r = Math.max(1.2, 30 / lamp[2]);
            g.setFill(locked ? Palette.RED : Palette.PHOSPHOR_DIM);
            g.fillOval(lamp[0] - r / 2, lamp[1] - r / 2, r, r);
        }
    }

    private void furniture(GraphicsContext g, Projection p, WorldView feed, RoomLayout room, Look look, List<Drawable> out) {
        for (Cell t : room.terminals()) {
            double cx = t.x() + 0.5;
            double cy = t.y() + 0.5;
            double depth = p.distance(cx, cy, 0.5);
            out.add(new Drawable() {
                @Override
                public double depth() {
                    return depth;
                }

                @Override
                public void draw() {
                    box(g, p, cx - 0.42, cy - 0.28, cx + 0.42, cy + 0.28, 0, 0.74, Color.web("#40453d"), look, depth);
                    box(g, p, cx - 0.24, cy - 0.12, cx + 0.24, cy + 0.12, 0.74, 1.16, Color.web("#2f332d"), look, depth);
                    double[] screen = p.project(cx, cy, 0.97);
                    if (screen != null) {
                        double r = Math.max(2, 70 / screen[2]);
                        g.setFill(Palette.PHOSPHOR.deriveColor(0, 1, 1, look.ir() ? 0.35 : 0.8));
                        g.fillRect(screen[0] - r * 0.6, screen[1] - r * 0.4, r * 1.2, r * 0.8);
                    }
                }
            });
        }
        for (Entity o : feed.ofKind(EntityKind.OBJECT)) {
            if (o.position() == null || !room.code().equals(feed.roomOf(o))) {
                continue;
            }
            double cx = o.position().x() + 0.5;
            double cy = o.position().y() + 0.5;
            double depth = p.distance(cx, cy, 0.4);
            out.add(new Drawable() {
                @Override
                public double depth() {
                    return depth;
                }

                @Override
                public void draw() {
                    prop(g, p, o, cx, cy, look, depth);
                }
            });
        }
    }

    private void prop(GraphicsContext g, Projection p, Entity o, double cx, double cy, Look look, double depth) {
        switch (o.name()) {
            case "CRATE-19" -> box(g, p, cx - 0.45, cy - 0.45, cx + 0.45, cy + 0.45, 0, 0.9, Color.web("#5a4e39"), look, depth);
            case "MANNEQUIN" -> {
                double[] foot = p.project(cx, cy, 0);
                double[] head = p.project(cx, cy, 1.62);
                if (foot != null && head != null) {
                    Color pale = look.ir() ? Color.web("#d7e3d8") : Color.web("#8a8a80");
                    Figures.draw(g, foot[0], foot[1], foot[1] - head[1], -1,
                            new Figures.Style(look.shade(pale, pale, depth, 1), null, false, null, 0.26));
                }
            }
            case "WHEELCHAIR" -> {
                box(g, p, cx - 0.3, cy - 0.25, cx + 0.3, cy + 0.25, 0.45, 0.52, Color.web("#3d4140"), look, depth);
                box(g, p, cx - 0.3, cy + 0.18, cx + 0.3, cy + 0.25, 0.52, 0.95, Color.web("#3d4140"), look, depth);
            }
            case "REEL-7" -> box(g, p, cx - 0.2, cy - 0.2, cx + 0.2, cy + 0.2, 0, 0.1, Color.web("#2a2a2a"), look, depth);
            default -> {
                box(g, p, cx - 0.35, cy - 0.3, cx + 0.35, cy + 0.3, 0, 0.72, Color.web("#3b3f38"), look, depth);
                box(g, p, cx - 0.12, cy - 0.1, cx + 0.12, cy + 0.1, 0.72, 0.8, Color.web("#8f8a78"), look, depth);
            }
        }
    }

    private void box(GraphicsContext g, Projection p, double x0, double y0, double x1, double y1, double z0, double z1,
                     Color color, Look look, double depth) {
        double[][][] faces = {
            {{x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}},
            {{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}},
            {{x1, y1, z0}, {x0, y1, z0}, {x0, y1, z1}, {x1, y1, z1}},
            {{x1, y0, z0}, {x1, y1, z0}, {x1, y1, z1}, {x1, y0, z1}},
            {{x0, y1, z0}, {x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}}};
        double[][] normals = {{0, 0, 1}, {0, -1, 0}, {0, 1, 0}, {1, 0, 0}, {-1, 0, 0}};
        double[] shade = {1.15, 0.8, 0.8, 0.65, 0.65};
        for (int i = 0; i < faces.length; i++) {
            double[] c = centre(faces[i]);
            if (!p.facing(c[0], c[1], c[2], normals[i][0], normals[i][1], normals[i][2])) {
                continue;
            }
            double[][] poly = p.polygon(faces[i]);
            if (poly != null) {
                g.setFill(look.shade(color, color.grayscale().brighter(), depth, shade[i]));
                g.fillPolygon(poly[0], poly[1], poly[0].length);
            }
        }
    }

    private static double[] centre(double[][] quad) {
        double[] c = new double[3];
        for (double[] v : quad) {
            c[0] += v[0] / quad.length;
            c[1] += v[1] / quad.length;
            c[2] += v[2] / quad.length;
        }
        return c;
    }

    private void figures(GraphicsContext g, Projection p, WorldView feed, WorldView live, RoomLayout room, Look look,
                         RenderInput in, List<Drawable> out, List<Hit> hits, PostFx fx, boolean thumbnail) {
        boolean isLive = feed == live;
        List<Entity> present = new ArrayList<>(feed.occupants(room.code()));
        for (Entity e : feed.entities()) {
            if (e.kind().mobile() && e.present() && feed.map().roomAt(e.position()) == null
                    && feed.map().doorAt(e.position()) != null && feed.map().doorAt(e.position()).connects(room.code())) {
                present.add(e);
            }
        }
        String watchedCamera = live.incident().observedCamera();
        for (Entity e : present) {
            double[] pos = isLive ? in.motion().position(e.id(), e.position(), in.alpha())
                    : new double[] {e.position().x() + 0.5, e.position().y() + 0.5};
            if (pos == null) {
                continue;
            }
            boolean unknown = e.kind() == EntityKind.UNKNOWN;
            double height = unknown ? UNKNOWN_HEIGHT : PERSON_HEIGHT;
            double[] foot = p.project(pos[0], pos[1], 0);
            double[] head = p.project(pos[0], pos[1], height);
            if (foot == null || head == null) {
                continue;
            }
            double depth = p.distance(pos[0], pos[1], 0.9);
            double px = foot[0];
            double py = foot[1];
            double ph = foot[1] - head[1];
            double stride = isLive && in.motion().moving(e.id()) ? in.alpha() : -1;
            Entity watched = watchedCamera == null ? null : live.camera(watchedCamera);
            boolean facing = e.state() == EntityState.AWARE && watched != null
                    && ((CameraFacet) watched.facet()).roomCode().equals(room.code());
            Figures.Style style = style(e, unknown, facing, look, depth);
            double jitter = unknown ? (fx.jitter() - 0.5) * Math.max(1, ph / 60) : 0;
            boolean selected = e.id().equals(in.selected());
            out.add(new Drawable() {
                @Override
                public double depth() {
                    return depth;
                }

                @Override
                public void draw() {
                    if (unknown && !look.ir()) {
                        // A faint double image, as if the tube could not settle on it.
                        g.save();
                        g.setGlobalAlpha(0.18);
                        Figures.draw(g, px + ph * 0.03, py, ph, -1, style);
                        g.restore();
                    }
                    Figures.draw(g, px + jitter, py, ph, stride, style);
                    if (selected && !thumbnail) {
                        tracking(g, px, py, ph, e, feed);
                    }
                }
            });
            hits.add(Figures.bounds(e.id(), px, py, ph, unknown ? 0.22 : 0.28));
        }
    }

    private static Figures.Style style(Entity e, boolean unknown, boolean facing, Look look, double depth) {
        if (unknown) {
            return new Figures.Style(Color.web("#010101"), null, false, null, 0.22);
        }
        if (look.ir()) {
            Color glow = look.shade(Color.WHITE, Color.web("#e4efe5"), depth, 1.1);
            return new Figures.Style(glow, null, facing, Color.WHITE, 0.28);
        }
        Color body = Color.web("#141715");
        Color rim = look.shade(Color.web("#7d877f"), Color.web("#7d877f"), depth, 0.8);
        return new Figures.Style(body, rim, facing, Color.web("#d8d4c6"), 0.28);
    }

    /** The OCR-style tracking box an operator gets when they select someone. */
    private void tracking(GraphicsContext g, double x, double y, double h, Entity e, WorldView feed) {
        double w = h * 0.5;
        double top = y - h - 4;
        double len = Math.max(4, w * 0.25);
        g.setStroke(Palette.PHOSPHOR);
        g.setLineWidth(1.2);
        double l = x - w / 2;
        double r = x + w / 2;
        double b = y + 3;
        g.strokeLine(l, top, l + len, top);
        g.strokeLine(l, top, l, top + len);
        g.strokeLine(r, top, r - len, top);
        g.strokeLine(r, top, r, top + len);
        g.strokeLine(l, b, l + len, b);
        g.strokeLine(l, b, l, b - len);
        g.strokeLine(r, b, r - len, b);
        g.strokeLine(r, b, r, b - len);
        Entity claimed = feed.entity(e.identity());
        String label = e.kind() == EntityKind.UNKNOWN && !e.impersonating() ? e.id() + " ???"
                : e.identity() + " " + (claimed == null ? e.name() : claimed.name());
        g.setFont(osdSmall);
        g.setFill(Palette.PHOSPHOR);
        g.setTextAlign(TextAlignment.LEFT);
        g.setTextBaseline(VPos.BOTTOM);
        g.fillText(label, l, top - 3);
    }

    private void osd(GraphicsContext g, double w, double h, CameraFacet cf, RoomLayout room, WorldView feed,
                     WorldView live, Look look, boolean looping, RenderInput in) {
        double pad = Math.max(10, w * 0.018);
        g.setTextBaseline(VPos.TOP);
        g.setTextAlign(TextAlignment.LEFT);
        g.setFill(Color.web("#e6e2d6", 0.92));
        g.setFont(osdLarge);
        g.fillText(cf.code(), pad, pad - 4);
        g.setFont(osdSmall);
        g.fillText(room.code() + "  " + room.label(), pad, pad + osdLarge.getSize() - 2);

        long shownTick = feed.tick();
        String time = FacilityClock.format(shownTick);
        g.setTextAlign(TextAlignment.RIGHT);
        g.setFont(osdLarge);
        g.fillText(time, w - pad, pad - 4);
        g.setFont(osdSmall);
        boolean blink = (in.nanos() / 500_000_000L) % 2 == 0;
        if (in.review()) {
            g.setFill(Palette.TEAL);
            g.fillText("◀◀ PLAYBACK", w - pad, pad + osdLarge.getSize() - 2);
        } else {
            g.setFill(blink ? Palette.RED : Palette.RED.deriveColor(0, 1, 0.4, 1));
            g.fillText("● REC", w - pad, pad + osdLarge.getSize() - 2);
        }

        g.setTextBaseline(VPos.BOTTOM);
        g.setTextAlign(TextAlignment.LEFT);
        g.setFill(Color.web("#e6e2d6", 0.8));
        List<String> tags = new ArrayList<>();
        if (look.ir()) {
            tags.add("IR");
        }
        LightMode mode = feed.light(room.code());
        if (mode != LightMode.ON) {
            tags.add("LIGHT " + mode);
        }
        CameraStatus status = ((CameraFacet) live.camera(cf.code()).facet()).status();
        if (status == CameraStatus.INTERFERENCE) {
            tags.add("SIGNAL WEAK");
        }
        g.fillText(String.join("   ", tags), pad, h - pad);
        g.setTextAlign(TextAlignment.RIGHT);
        g.fillText(String.format("T+%06d", shownTick), w - pad, h - pad);
        if (looping && (in.nanos() / 700_000_000L) % 4 != 0) {
            // The clock is the tell: this feed is not showing now.
            g.setFill(Color.web("#e6e2d6", 0.35));
            g.setTextAlign(TextAlignment.RIGHT);
            g.fillText("SYNC", w - pad, h - pad - osdSmall.getSize() - 4);
        }
    }

    private void noSignal(GraphicsContext g, double w, double h, CameraFacet cf, RenderInput in, PostFx fx) {
        fx.interference(g, w, h, 1.0);
        fx.scanlines(g, w, h, 0.3);
        g.setFill(Color.web("#e6e2d6", 0.9));
        g.setFont(osdLarge);
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.CENTER);
        g.fillText(cf.code() + "  NO SIGNAL", w / 2, h / 2);
        fx.vignette(g, w, h, 0.9);
    }
}
