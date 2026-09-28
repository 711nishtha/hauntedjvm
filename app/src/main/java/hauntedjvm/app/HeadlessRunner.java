package hauntedjvm.app;

import hauntedjvm.core.Simulations;
import hauntedjvm.core.config.SimulationConfig;
import hauntedjvm.core.engine.SimulationEngine;
import hauntedjvm.core.event.AnomalyEvent;
import hauntedjvm.core.event.CommunicationEvent;
import hauntedjvm.core.event.EntityEvent;
import hauntedjvm.core.event.EventLog;
import hauntedjvm.core.event.EventRecord;
import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityState;
import hauntedjvm.core.incident.EscalationLevel;
import hauntedjvm.core.telemetry.SimulationMetrics;
import hauntedjvm.core.time.FacilityClock;
import hauntedjvm.core.timeline.LogDigest;
import hauntedjvm.persistence.IncidentReport;
import hauntedjvm.persistence.Investigation;
import hauntedjvm.persistence.SessionArchive;
import java.io.IOException;
import java.io.PrintStream;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Runs a night without a window, as fast as the machine allows, and prints what the incident
 * system would have logged. Also the quickest way to check reproducibility from a shell: the
 * same seed prints the same digest on every machine.
 */
final class HeadlessRunner {

    private final LaunchOptions options;
    private final PrintStream out;

    HeadlessRunner(LaunchOptions options, PrintStream out) {
        this.options = options;
        this.out = out;
    }

    int run(long seed) throws IOException {
        SimulationConfig config = SimulationConfig.defaults(seed).withPersons(options.persons());
        out.printf("HAUNTEDJVM // FACILITY-07 // headless%n");
        out.printf("seed %s   staff %d   ticks %d%n%n", config.seedHex(), config.persons(), options.ticks());
        long started = System.nanoTime();
        SimulationEngine engine = Simulations.create(config);
        EventLog log = engine.timeline().log();
        long printed = 0;
        for (long t = 0; t < options.ticks(); t++) {
            engine.step();
            printed = echo(engine, log, printed);
            if (engine.tick() % 1_000 == 0) {
                SimulationMetrics m = SimulationMetrics.of(engine.world());
                out.printf("  -- %s  level %d  events %,d  anomalies %d  missing %d  corrupted %d%n",
                        FacilityClock.format(engine.tick()), engine.world().incident().level(), log.size(),
                        m.totalAnomalies(), m.missing(), m.corrupted());
            }
        }
        double seconds = (System.nanoTime() - started) / 1e9;
        SimulationMetrics m = SimulationMetrics.of(engine.world());
        out.printf("%n== END OF RECORDING %s ==%n", FacilityClock.format(engine.tick()));
        out.printf("final level     %d (%s)%n", engine.world().incident().level(),
                engine.world().incident().escalation().name());
        out.printf("anomalies       %d%n", m.totalAnomalies());
        out.printf("missing         %d   corrupted %d   aware %d%n", m.missing(), m.corrupted(), m.aware());
        out.printf("events          %,d%n", log.size());
        out.printf("throughput      %,.0f ticks/s (%.2fs)%n", options.ticks() / seconds, seconds);
        out.printf("log digest      %s%n", LogDigest.of(log));

        if (options.save() != null || options.report() != null) {
            Investigation inv = new Investigation(UUID.randomUUID().toString(), null, Instant.now(), config,
                    engine.timeline(), engine.tick(), log.size(), List.of(), Set.of());
            if (options.save() != null) {
                new SessionArchive(engine.map()).save(inv, options.save());
                out.printf("session saved   %s%n", options.save().toAbsolutePath());
            }
            if (options.report() != null) {
                IncidentReport.write(inv, engine.world(), options.report());
                out.printf("report written  %s%n", options.report().toAbsolutePath());
            }
        }
        return 0;
    }

    /** Prints the notable records appended since {@code from}. */
    private long echo(SimulationEngine engine, EventLog log, long from) {
        long end = log.size();
        for (long s = from; s < end; s++) {
            EventRecord r = log.get(s);
            String line = switch (r.event()) {
                case AnomalyEvent.AnomalyEscalated e ->
                        "INCIDENT LEVEL " + e.to() + " (" + EscalationLevel.of(e.to()).name() + ")";
                case AnomalyEvent.AnomalyDetected e when e.anomaly().severity() >= 3 || options.debug() ->
                        "[" + e.anomaly().category() + "/" + e.anomaly().severity() + "] " + e.anomaly().summary();
                case AnomalyEvent.PerturbationApplied e when options.debug() -> "(director) " + e.name() + " " + e.detail();
                case CommunicationEvent.MessageSent e when e.sender() == null ->
                        e.channel() + ": \"" + e.text() + "\" (no sender)";
                case EntityEvent.EntityStateChanged e when e.to() == EntityState.MISSING -> name(engine, e.id())
                        + " is missing: " + e.reason();
                default -> null;
            };
            if (line != null) {
                out.printf("%s  %s%n", FacilityClock.format(r.tick()), line);
            }
        }
        return end;
    }

    private static String name(SimulationEngine engine, hauntedjvm.core.entity.EntityId id) {
        Entity e = engine.world().entity(id);
        return e == null ? id.toString() : e.name() + " " + id;
    }
}
