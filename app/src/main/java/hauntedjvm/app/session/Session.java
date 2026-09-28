package hauntedjvm.app.session;

import hauntedjvm.app.runtime.SimulationRunner;
import hauntedjvm.app.runtime.Speed;
import hauntedjvm.core.Simulations;
import hauntedjvm.core.config.SimulationConfig;
import hauntedjvm.core.engine.SimulationEngine;
import hauntedjvm.persistence.Investigation;
import hauntedjvm.persistence.InvestigationNote;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * One open investigation: the running simulation plus what the operator has noted.
 * Notes and discoveries are touched from the JavaFX thread only.
 */
public final class Session implements AutoCloseable {

    private final String id;
    private final Instant createdAt;
    private final String title;
    private final SimulationRunner runner;
    private final List<InvestigationNote> notes = new ArrayList<>();
    private final Set<String> discovered = new TreeSet<>();
    private boolean dirty;

    private Session(String id, Instant createdAt, String title, SimulationRunner runner) {
        this.id = id;
        this.createdAt = createdAt;
        this.title = title;
        this.runner = runner;
    }

    public static Session fresh(SimulationConfig config, Speed speed, double baseTicksPerSecond) {
        SimulationEngine engine = Simulations.create(config);
        return new Session(UUID.randomUUID().toString(), Instant.now(), null,
                new SimulationRunner(engine, speed, baseTicksPerSecond, false));
    }

    /** Wraps an engine that has already been run for a while; used for demo scenes. */
    public static Session fromEngine(SimulationEngine engine, Speed speed, double baseTicksPerSecond, boolean paused) {
        return new Session(UUID.randomUUID().toString(), Instant.now(), null,
                new SimulationRunner(engine, speed, baseTicksPerSecond, paused));
    }

    /** Reopens a saved investigation, paused at the point it was saved. */
    public static Session reopen(Investigation inv, Speed speed, double baseTicksPerSecond) {
        SimulationEngine engine = Simulations.resume(inv.config(), inv.timeline());
        Session s = new Session(inv.sessionId(), inv.createdAt(), inv.title(),
                new SimulationRunner(engine, speed, baseTicksPerSecond, true));
        s.notes.addAll(inv.notes());
        s.discovered.addAll(inv.discovered());
        return s;
    }

    public SimulationRunner runner() {
        return runner;
    }

    public SimulationConfig config() {
        return runner.config();
    }

    public List<InvestigationNote> notes() {
        return List.copyOf(notes);
    }

    public void addNote(InvestigationNote note) {
        notes.add(note);
        dirty = true;
    }

    public void removeNote(UUID noteId) {
        dirty |= notes.removeIf(n -> n.id().equals(noteId));
    }

    public boolean discovered(String anomalyId) {
        return discovered.contains(anomalyId);
    }

    public void discover(String anomalyId) {
        dirty |= discovered.add(anomalyId);
    }

    public boolean dirty() {
        return dirty;
    }

    public void markSaved() {
        dirty = false;
    }

    /** A consistent snapshot of the whole investigation, cut between ticks on the simulation thread. */
    public CompletableFuture<Investigation> capture() {
        List<InvestigationNote> notesNow = notes();
        Set<String> discoveredNow = Set.copyOf(discovered);
        return runner.capture().thenApply(cut -> new Investigation(id, title, createdAt, cut.config(), cut.timeline(),
                cut.headTick(), cut.eventCount(), notesNow, discoveredNow));
    }

    @Override
    public void close() {
        runner.close();
    }
}
