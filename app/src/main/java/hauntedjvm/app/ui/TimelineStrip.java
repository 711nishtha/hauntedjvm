package hauntedjvm.app.ui;

import hauntedjvm.app.render.Palette;
import hauntedjvm.core.event.AnomalyEvent;
import hauntedjvm.core.event.EventLog;
import hauntedjvm.core.event.EventRecord;
import hauntedjvm.core.event.OperatorEvent;
import hauntedjvm.core.incident.AnomalyCategory;
import hauntedjvm.core.time.FacilityClock;
import hauntedjvm.core.timeline.Timeline;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongConsumer;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Pane;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

/**
 * The recording as a strip: event density, anomalies, escalation, rewinds. Click or drag to
 * move the playhead; scroll to zoom.
 *
 * <p>Like the log, the strip reads the timeline incrementally and keeps compact aggregates
 * (bucketed counts and a marker list), so drawing costs the same whether the night has been
 * running for five minutes or five hours.
 */
final class TimelineStrip extends Pane {

    private static final int BUCKET = 10;

    private record Marker(long tick, AnomalyCategory category, int severity) {
    }

    private record Change(long tick, int level) {
    }

    private final Canvas canvas = new Canvas();
    private final LongConsumer seek;
    private final Font font = Fonts.mono(10);
    private Timeline timeline;
    private long scanned;
    private int[] buckets = new int[256];
    private final List<Marker> markers = new ArrayList<>();
    private final List<Change> levels = new ArrayList<>();
    private final List<Long> rewinds = new ArrayList<>();
    private long head;
    private long playhead = -1;
    private double zoom = 1;
    private double hoverX = -1;

    TimelineStrip(LongConsumer seek) {
        this.seek = seek;
        getChildren().add(canvas);
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());
        setMinHeight(64);
        setPrefHeight(78);
        canvas.setOnMousePressed(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                seek.accept(tickAt(e.getX()));
            }
        });
        canvas.setOnMouseDragged(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                seek.accept(tickAt(e.getX()));
            }
        });
        canvas.setOnMouseMoved(e -> hoverX = e.getX());
        canvas.setOnMouseExited(e -> hoverX = -1);
        canvas.setOnScroll(e -> {
            zoom = Math.clamp(zoom * (e.getDeltaY() > 0 ? 1.25 : 0.8), 1, 400);
            e.consume();
        });
    }

    void update(Timeline current, long eventCount, long headTick, long playheadTick) {
        if (current != timeline) {
            timeline = current;
            scanned = 0;
            Arrays.fill(buckets, 0);
            markers.clear();
            levels.clear();
            rewinds.clear();
        }
        head = headTick;
        playhead = playheadTick;
        EventLog log = timeline.log();
        for (long s = scanned; s < eventCount; s++) {
            EventRecord r = log.get(s);
            int b = (int) (r.tick() / BUCKET);
            if (b >= buckets.length) {
                buckets = Arrays.copyOf(buckets, Math.max(b + 1, buckets.length * 2));
            }
            buckets[b]++;
            switch (r.event()) {
                case AnomalyEvent.AnomalyDetected d -> markers.add(new Marker(r.tick(), d.anomaly().category(),
                        d.anomaly().severity()));
                case AnomalyEvent.AnomalyEscalated e -> levels.add(new Change(r.tick(), e.to()));
                case OperatorEvent.TimelineRewound t -> rewinds.add(r.tick());
                default -> {
                    // not marked on the strip
                }
            }
        }
        scanned = eventCount;
        draw();
    }

    private long windowEnd() {
        return Math.max(60, head);
    }

    private long windowStart() {
        long span = (long) Math.max(60, windowEnd() / zoom);
        long anchor = playhead >= 0 ? playhead : head;
        long start = anchor - span / 2;
        if (playhead < 0 || start + span > windowEnd()) {
            start = windowEnd() - span;
        }
        return Math.max(0, start);
    }

    private long tickAt(double x) {
        long start = windowStart();
        long span = windowEnd() - start;
        double pad = 12;
        double frac = Math.clamp((x - pad) / (canvas.getWidth() - pad * 2), 0.0, 1.0);
        return start + Math.round(frac * span);
    }

    private void draw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        g.setFill(Palette.PANEL);
        g.fillRect(0, 0, w, h);
        double pad = 12;
        double plotW = w - pad * 2;
        long start = windowStart();
        long end = windowEnd();
        double span = Math.max(1, end - start);
        double top = 6;
        double bottom = h - 16;

        // Escalation level as a band along the top.
        int level = 0;
        long from = 0;
        for (Change c : levels) {
            band(g, from, c.tick(), level, start, span, pad, plotW, top);
            from = c.tick();
            level = c.level();
        }
        band(g, from, end, level, start, span, pad, plotW, top);

        // Event density.
        int firstBucket = (int) (start / BUCKET);
        int lastBucket = (int) Math.min(buckets.length - 1, end / BUCKET);
        int max = 1;
        for (int b = firstBucket; b <= lastBucket; b++) {
            max = Math.max(max, buckets[b]);
        }
        g.setFill(Palette.PHOSPHOR_FAINT);
        double barW = Math.max(1, plotW * BUCKET / span);
        for (int b = firstBucket; b <= lastBucket; b++) {
            double x = pad + (b * (double) BUCKET - start) / span * plotW;
            double bh = (bottom - top - 8) * Math.sqrt(buckets[b] / (double) max) * 0.6;
            g.fillRect(x, bottom - bh, barW, bh);
        }

        marks(g, start, end, span, pad, plotW, top, bottom);
        labels(g, w, start, end, span, pad, plotW, top, bottom);
    }

    private void marks(GraphicsContext g, long start, long end, double span, double pad, double plotW, double top,
                       double bottom) {
        for (Marker m : markers) {
            if (m.tick() < start || m.tick() > end) {
                continue;
            }
            double x = pad + (m.tick() - start) / span * plotW;
            g.setStroke(Palette.category(m.category()));
            g.setLineWidth(m.severity() >= 4 ? 2 : 1);
            g.strokeLine(x, bottom, x, bottom - 6 - m.severity() * 7);
        }
        g.setFill(Palette.TEAL);
        for (long t : rewinds) {
            double x = pad + (t - start) / span * plotW;
            g.fillPolygon(new double[] {x - 4, x + 4, x}, new double[] {top + 6, top + 6, top + 12}, 3);
        }
    }

    private void labels(GraphicsContext g, double w, long start, long end, double span, double pad, double plotW,
                        double top, double bottom) {

        // Time labels.
        g.setFont(font);
        g.setFill(Palette.MUTED);
        g.setTextBaseline(VPos.TOP);
        g.setTextAlign(TextAlignment.CENTER);
        long step = niceStep(span / Math.max(1, plotW / 90));
        for (long t = (start / step + 1) * step; t < end; t += step) {
            double x = pad + (t - start) / span * plotW;
            g.fillText(FacilityClock.format(t).substring(0, 5), x, bottom + 2);
            g.fillRect(x, bottom, 1, 3);
        }

        // Head and playhead.
        double hx = pad + (head - start) / span * plotW;
        g.setStroke(Palette.PHOSPHOR);
        g.setLineWidth(1.5);
        g.strokeLine(hx, top, hx, bottom);
        if (playhead >= 0) {
            double px = pad + (playhead - start) / span * plotW;
            g.setStroke(Palette.AMBER);
            g.setLineWidth(2);
            g.strokeLine(px, top, px, bottom);
            g.setFill(Palette.AMBER);
            g.setTextAlign(TextAlignment.LEFT);
            g.fillText(FacilityClock.format(playhead), Math.min(px + 4, w - 70), top + 2);
        }
        if (hoverX >= 0) {
            long t = tickAt(hoverX);
            g.setStroke(Palette.PAPER.deriveColor(0, 1, 1, 0.3));
            g.setLineWidth(1);
            g.strokeLine(hoverX, top, hoverX, bottom);
            g.setFill(Palette.PAPER);
            g.setTextAlign(TextAlignment.LEFT);
            g.fillText(FacilityClock.format(t), Math.min(hoverX + 4, w - 70), bottom - 14);
        }
        if (zoom > 1) {
            g.setFill(Palette.MUTED);
            g.setTextAlign(TextAlignment.RIGHT);
            g.fillText(String.format("zoom %.0f×", zoom), w - pad, top + 2);
        }
    }

    private static void band(GraphicsContext g, long from, long to, int level, long start, double span, double pad,
                             double plotW, double top) {
        double x0 = pad + Math.max(0, from - start) / span * plotW;
        double x1 = pad + Math.max(0, to - start) / span * plotW;
        if (x1 <= x0) {
            return;
        }
        g.setFill(Palette.level(level).deriveColor(0, 1, 1, 0.8));
        g.fillRect(x0, top, x1 - x0, 3);
    }

    private static long niceStep(double raw) {
        long[] steps = {60, 120, 300, 600, 900, 1800, 3600, 7200};
        for (long s : steps) {
            if (s >= raw) {
                return s;
            }
        }
        return 14_400;
    }
}
