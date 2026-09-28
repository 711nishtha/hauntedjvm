package hauntedjvm.app.ui;

import hauntedjvm.app.render.Palette;
import hauntedjvm.core.anomaly.Escalation;
import hauntedjvm.core.entity.EntityKind;
import hauntedjvm.core.entity.EntityState;
import hauntedjvm.core.incident.EscalationLevel;
import hauntedjvm.core.telemetry.SimulationMetrics;
import hauntedjvm.core.time.FacilityClock;
import hauntedjvm.diagnostics.JvmSnapshot;
import hauntedjvm.diagnostics.TelemetryService;
import hauntedjvm.diagnostics.ThreadInspector;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Three clearly separated readouts.
 *
 * <ul>
 *   <li><b>REAL JVM</b>: measured from the JVM this application runs in, via MXBeans and JFR.</li>
 *   <li><b>SIMULATION</b>: figures about the simulated facility, computed from its state.</li>
 *   <li><b>INCIDENT</b>: the fictional incident system's own presentation of the simulation.</li>
 * </ul>
 * Nothing moves between sections, and each carries a tag saying which kind of truth it tells.
 */
final class TelemetryPanel extends ScrollPane {

    private final TelemetryService telemetry;
    private final ThreadInspector threads = new ThreadInspector();
    private final Consumer<Selection> select;
    private final Widgets.KeyValues real = new Widgets.KeyValues();
    private final Widgets.KeyValues sim = new Widgets.KeyValues();
    private final Widgets.KeyValues incident = new Widgets.KeyValues();
    private final Widgets.Sparkline heapLine = new Widgets.Sparkline(280, 26, Palette.PHOSPHOR);
    private final Widgets.Sparkline cpuLine = new Widgets.Sparkline(280, 26, Palette.AMBER);
    private final Widgets.Meter levelMeter = new Widgets.Meter(280);
    private final VBox threadList = new VBox(1);
    private final Label threadsTitle = Widgets.label("", "dim");
    private long lastThreadRefresh;
    private double fps;

    TelemetryPanel(TelemetryService telemetry, Consumer<Selection> select) {
        this.telemetry = telemetry;
        this.select = select;
        VBox content = new VBox(2);
        content.setPadding(new Insets(0, 0, 12, 0));
        content.getChildren().addAll(
                Widgets.sectionTitle("REAL JVM", "real", Widgets.tag("REAL", "real")),
                padded(Widgets.label("measured from this process; not simulated", "dim")),
                padded(heapLine), real, padded(cpuLine),
                padded(threadsTitle), padded(threadList),
                Widgets.sectionTitle("SIMULATION", "sim", Widgets.tag("SIMULATED", "sim")),
                padded(Widgets.label("the facility model: entities, events, clock", "dim")),
                sim,
                Widgets.sectionTitle("INCIDENT SYSTEM", "fiction", Widgets.tag("FICTIONAL", "fiction")),
                padded(Widgets.label("the facility's incident software, as the story tells it", "dim")),
                padded(levelMeter), incident);
        setContent(content);
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
    }

    private static HBox padded(javafx.scene.Node n) {
        HBox box = new HBox(n);
        box.setPadding(new Insets(2, 10, 2, 10));
        return box;
    }

    void setFps(double value) {
        fps = value;
    }

    void refresh(Display d) {
        refreshReal();
        refreshSimulation(d);
        refreshIncident(d);
    }

    private void refreshReal() {
        JvmSnapshot j = telemetry.latest();
        if (j == null) {
            return;
        }
        List<JvmSnapshot> history = telemetry.history();
        heapLine.update(history.stream().map(s -> (double) s.heapUsed()).toList(),
                j.heapMax() > 0 ? j.heapMax() : j.heapCommitted());
        cpuLine.update(history.stream().map(s -> Math.max(0, s.processCpuLoad())).toList(), 1.0);
        real.set("heap", String.format("%s / %s (%.0f%%)", mb(j.heapUsed()), mb(j.heapMax() > 0 ? j.heapMax()
                : j.heapCommitted()), j.heapFraction() * 100), j.heapFraction() > 0.85 ? "warn" : null);
        real.set("non-heap", mb(j.nonHeapUsed()));
        real.set("threads", j.threads() + " live, " + j.daemonThreads() + " daemon, peak " + j.peakThreads());
        real.set("cpu (process)", j.processCpuLoad() < 0 ? "unavailable" : String.format("%.1f%%", j.processCpuLoad() * 100));
        real.set("cpu (system)", j.systemCpuLoad() < 0 ? "unavailable"
                : String.format("%.1f%% of %d cores", j.systemCpuLoad() * 100, j.availableProcessors()));
        real.set("gc", j.gcCollections() + " collections, " + j.gcTimeMillis() + " ms total");
        real.set("last gc pause", j.lastGcPauseMillis() < 0 ? (telemetry.jfrActive() ? "none yet" : "JFR off")
                : String.format("%.2f ms (JFR)", j.lastGcPauseMillis()));
        real.set("allocation", j.allocationRate() < 0 ? "JFR off" : mb((long) j.allocationRate()) + "/s (JFR)");
        real.set("uptime", format(Duration.ofMillis(j.uptimeMillis())));
        real.set("classes", String.valueOf(j.loadedClasses()));
        real.set("jit", j.jitMillis() < 0 ? "n/a" : j.jitMillis() + " ms");
        real.set("render", String.format("%.0f fps", fps));
        real.set("vm", j.vmName() + " " + j.vmVersion());
        long now = System.nanoTime();
        if (now - lastThreadRefresh > 2_000_000_000L) {
            lastThreadRefresh = now;
            refreshThreads();
        }
    }

    private void refreshThreads() {
        List<ThreadInspector.ThreadView> list = threads.threads();
        threadsTitle.setText("platform threads (" + list.size() + "; virtual threads are not listed by the JVM)");
        threadList.getChildren().clear();
        for (ThreadInspector.ThreadView t : list) {
            Label row = Widgets.label(String.format("%-28s %s%s", truncate(t.name(), 28), t.state(),
                    t.daemon() ? " d" : ""), "kv-value");
            row.getStyleClass().add("link");
            row.setOnMouseClicked(e -> select.accept(new Selection.OfThread(t.id(), t.name())));
            threadList.getChildren().add(row);
        }
    }

    private void refreshSimulation(Display d) {
        SimulationMetrics m = SimulationMetrics.of(d.world());
        var status = d.frame().status();
        sim.set("tick", d.tick() + (d.review() ? "  (playhead; head " + d.frame().status().tick() + ")" : ""));
        sim.set("facility time", FacilityClock.format(d.tick()) + "  (+" + FacilityClock.duration(d.tick()) + ")");
        sim.set("speed", status.speed().label() + String.format("  %.1f / %.1f ticks/s", status.measuredTps(),
                status.targetTps()), status.paused() ? "warn" : null);
        sim.set("tick cost", String.format("%.0f µs", status.stepMicros()));
        sim.set("entities", m.entities() + " (" + m.byKind().getOrDefault(EntityKind.PERSON, 0) + " staff, "
                + m.byKind().getOrDefault(EntityKind.PROCESS, 0) + " processes, "
                + m.byKind().getOrDefault(EntityKind.DEVICE, 0) + " devices)");
        sim.set("staff states", states(m.personStates()));
        sim.set("corrupted", String.valueOf(m.corrupted()), m.corrupted() > 0 ? "warn" : null);
        sim.set("missing", String.valueOf(m.missing()), m.missing() > 0 ? "bad" : null);
        sim.set("unaccounted", String.valueOf(m.unaccounted()), m.unaccounted() > 0 ? "bad" : null);
        sim.set("mean fear", String.format("%.0f%%", m.meanFear() * 100));
        sim.set("mean awareness", String.format("%.0f%%", m.meanAwareness() * 100));
        sim.set("events", String.format("%,d", d.frame().eventCount()));
        sim.set("checkpoints", String.valueOf(d.frame().timeline().snapshots().size()));
        sim.set("timeline divergence", String.format("%.2f", m.divergence()), m.divergence() > 0.5 ? "warn" : null);
    }

    private void refreshIncident(Display d) {
        EscalationLevel level = d.world().incident().escalation();
        Escalation.Reading r = Escalation.read(d.world());
        levelMeter.update((level.number() + 1) / 6.0, Palette.level(level.number()));
        SimulationMetrics m = SimulationMetrics.of(d.world());
        incident.set("level", level.label(), level.number() >= 4 ? "bad" : level.number() >= 2 ? "warn" : null);
        incident.set("instability", String.format("%.2f  (anomalies %.1f, fear %.1f, awareness %.1f, corruption %.1f, "
                + "missing %.1f, unaccounted %.1f, rewinds %.1f)", r.total(), r.anomalies(), r.fear(), r.awareness(),
                r.corruption(), r.missing(), r.unknown(), r.rewinds()));
        incident.set("severity", level.number() == 0 ? "NOMINAL" : level.number() < 3 ? "UNDETERMINED" : "UNKNOWN",
                level.number() >= 3 ? "bad" : null);
        incident.set("origin", level.number() == 0 ? "—" : "UNKNOWN");
        incident.set("open anomalies", m.activeAnomalies() + " of " + m.totalAnomalies() + " logged");
        incident.set("object refs not found", String.valueOf(m.orphanRefs()), m.orphanRefs() > 0 ? "warn" : null);
        incident.set("badges without wearers", String.valueOf(m.badgesWithoutWearers()),
                m.badgesWithoutWearers() > 0 ? "bad" : null);
        incident.set("minds nobody remembers", String.valueOf(m.forgotten()), m.forgotten() > 0 ? "warn" : null);
        incident.set("timeline rewinds", String.valueOf(d.world().incident().rewinds()),
                d.world().incident().rewinds() > 0 ? "warn" : null);
    }

    private static String states(Map<EntityState, Integer> states) {
        StringBuilder sb = new StringBuilder();
        states.forEach((s, n) -> sb.append(sb.isEmpty() ? "" : ", ").append(n).append(' ').append(s.name().toLowerCase()));
        return sb.toString();
    }

    private static String mb(long bytes) {
        return String.format("%.0f MB", bytes / 1048576.0);
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private static String format(Duration d) {
        return String.format("%02d:%02d:%02d", d.toHours(), d.toMinutesPart(), d.toSecondsPart());
    }
}
