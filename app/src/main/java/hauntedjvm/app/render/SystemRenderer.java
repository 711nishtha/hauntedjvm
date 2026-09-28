package hauntedjvm.app.render;

import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityId;
import hauntedjvm.core.entity.EntityKind;
import hauntedjvm.core.entity.EntityState;
import hauntedjvm.core.entity.ProcessFacet;
import hauntedjvm.core.state.WorldView;
import hauntedjvm.core.time.FacilityClock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

/**
 * The facility's process table. These are simulated processes with simulated heaps; the view
 * says so in its title. Real JVM figures live only in the telemetry panel.
 */
public final class SystemRenderer {

    private static final int HISTORY = 90;

    private final Font title;
    private final Font body;
    private final Font big;
    private final Map<EntityId, Deque<Long>> heapHistory = new HashMap<>();
    private long lastTick = -1;

    public SystemRenderer(Font title, Font body, Font big) {
        this.title = title;
        this.body = body;
        this.big = big;
    }

    public List<Hit> render(GraphicsContext g, double w, double h, RenderInput in, PostFx fx) {
        WorldView world = in.world();
        List<Entity> processes = world.ofKind(EntityKind.PROCESS);
        record(world, processes);
        g.setFill(Palette.BACKGROUND);
        g.fillRect(0, 0, w, h);
        if (w < 320) {
            return compact(g, w, h, processes, fx);
        }
        g.setTextAlign(TextAlignment.LEFT);
        g.setTextBaseline(VPos.TOP);
        g.setFont(title);
        g.setFill(Palette.PHOSPHOR);
        g.fillText("SYSTEM // FACILITY-07 PROCESS TABLE", 16, 10);
        g.setFont(body);
        g.setFill(Palette.AMBER);
        g.fillText("SIMULATED — these are fictional facility processes, not processes on this computer", 16,
                14 + title.getSize());

        int cols = w > 900 ? 4 : w > 560 ? 3 : 2;
        int rows = (int) Math.ceil(processes.size() / (double) cols);
        double top = 26 + title.getSize() + body.getSize();
        double gap = 12;
        double cw = (w - 32 - gap * (cols - 1)) / cols;
        double ch = Math.min(170, (h - top - 20 - gap * (rows - 1)) / Math.max(1, rows));
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < processes.size(); i++) {
            Entity p = processes.get(i);
            double x = 16 + (i % cols) * (cw + gap);
            double y = top + (i / cols) * (ch + gap);
            card(g, p, x, y, cw, ch, world, in);
            hits.add(new Hit(p.id(), x, y, cw, ch));
        }
        fx.grain(g, w, h, 0.05);
        fx.scanlines(g, w, h, 0.16);
        fx.vignette(g, w, h, 0.55);
        return hits;
    }

    /** Thumbnail: one lamp per process, lit while it runs. */
    private List<Hit> compact(GraphicsContext g, double w, double h, List<Entity> processes, PostFx fx) {
        int cols = 4;
        double cw = w / cols;
        double ch = h / Math.max(1, Math.ceil(processes.size() / (double) cols));
        for (int i = 0; i < processes.size(); i++) {
            Entity p = processes.get(i);
            ProcessFacet pf = (ProcessFacet) p.facet();
            double x = (i % cols) * cw;
            double y = (i / cols) * ch;
            boolean running = p.state() == EntityState.RUNNING;
            boolean ghost = !running && pf.lastActivity() > pf.terminatedTick();
            g.setFill(running ? Palette.PHOSPHOR_DIM : ghost ? Palette.AMBER : Palette.RED.darker());
            g.fillRect(x + 6, y + 6, cw - 12, ch - 12);
        }
        fx.scanlines(g, w, h, 0.2);
        return List.of();
    }

    private void record(WorldView world, List<Entity> processes) {
        if (world.tick() == lastTick) {
            return;
        }
        if (world.tick() < lastTick) {
            heapHistory.clear();
        }
        lastTick = world.tick();
        for (Entity p : processes) {
            Deque<Long> d = heapHistory.computeIfAbsent(p.id(), k -> new ArrayDeque<>());
            d.addLast(((ProcessFacet) p.facet()).heapBytes());
            while (d.size() > HISTORY) {
                d.removeFirst();
            }
        }
    }

    private void card(GraphicsContext g, Entity p, double x, double y, double w, double h, WorldView world,
                      RenderInput in) {
        ProcessFacet pf = (ProcessFacet) p.facet();
        boolean running = p.state() == EntityState.RUNNING;
        boolean activeAfterDeath = !running && pf.lastActivity() > pf.terminatedTick();
        boolean selected = p.id().equals(in.selected());
        g.setFill(Palette.PANEL_RAISED);
        g.fillRect(x, y, w, h);
        g.setStroke(selected ? Palette.PAPER : running ? Palette.PHOSPHOR_DIM : activeAfterDeath ? Palette.AMBER : Palette.LINE);
        g.setLineWidth(selected ? 1.5 : 1);
        g.strokeRect(x + 0.5, y + 0.5, w - 1, h - 1);

        double pad = 10;
        g.setTextAlign(TextAlignment.LEFT);
        g.setTextBaseline(VPos.TOP);
        g.setFont(big);
        g.setFill(running ? Palette.PAPER : Palette.MUTED);
        g.fillText(pf.command(), x + pad, y + pad - 4);
        g.setFont(body);
        double line = body.getSize() + 4;
        double ty = y + pad + big.getSize();
        boolean impersonating = pf.claimedPid() != pf.pid();
        g.setFill(impersonating ? Palette.RED : Palette.MUTED);
        g.fillText("pid " + pf.claimedPid() + (impersonating ? " (reg. " + pf.pid() + ")" : "") + "  " + pf.host(),
                x + pad, ty);
        ty += line;
        g.setFill(Palette.state(p.state()));
        String status = p.state().name();
        if (!running) {
            status += " at " + FacilityClock.format(pf.terminatedTick())
                    + (pf.stopReason() == null ? "" : " (" + pf.stopReason() + ")");
        }
        g.fillText(status, x + pad, ty);
        ty += line;
        g.setFill(Palette.MUTED);
        g.fillText("restarts " + pf.restarts() + "  active " + FacilityClock.format(pf.lastActivity()), x + pad, ty);
        ty += line + 4;

        long budget = (48L + (pf.pid() % 64)) << 20;
        double frac = Math.min(1, pf.heapBytes() / (double) budget);
        g.setFill(Palette.LINE);
        g.fillRect(x + pad, ty, w - pad * 2, 6);
        g.setFill(frac > 0.85 ? Palette.AMBER : Palette.PHOSPHOR_DIM);
        g.fillRect(x + pad, ty, (w - pad * 2) * frac, 6);
        g.setFill(Palette.MUTED);
        g.fillText(String.format("sim heap %.1f MB", pf.heapBytes() / 1048576.0), x + pad, ty + 9);

        Deque<Long> hist = heapHistory.get(p.id());
        if (hist != null && hist.size() > 1 && y + h - (ty + 26) > 12) {
            sparkline(g, hist, budget, x + pad, ty + 26, w - pad * 2, y + h - pad - (ty + 26),
                    activeAfterDeath ? Palette.AMBER : Palette.PHOSPHOR_DIM);
        }
        if (activeAfterDeath && (in.nanos() / 300_000_000L) % 2 == 0) {
            g.setFill(Palette.AMBER);
            g.fillOval(x + w - pad - 8, y + pad, 7, 7);
        } else if (running) {
            g.setFill(Palette.PHOSPHOR);
            g.fillOval(x + w - pad - 8, y + pad, 7, 7);
        }
    }

    private static void sparkline(GraphicsContext g, Deque<Long> values, long max, double x, double y, double w, double h,
                                  Color color) {
        g.setStroke(color);
        g.setLineWidth(1);
        double step = w / (HISTORY - 1);
        double px = x + w - (values.size() - 1) * step;
        Double prevY = null;
        double prevX = 0;
        for (long v : values) {
            double vy = y + h - Math.min(1, v / (double) max) * h;
            if (prevY != null) {
                g.strokeLine(prevX, prevY, px, vy);
            }
            prevX = px;
            prevY = vy;
            px += step;
        }
    }
}
