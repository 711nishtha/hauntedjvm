package hauntedjvm.core.anomaly;

import hauntedjvm.core.engine.SimulationSystem;
import hauntedjvm.core.engine.TickContext;
import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.event.AnomalyEvent.AnomalyRetracted;
import hauntedjvm.core.event.AnomalyEvent.PerturbationApplied;
import hauntedjvm.core.event.EntityEvent.MarkChanged;
import hauntedjvm.core.incident.AnomalyRecord;
import hauntedjvm.core.incident.IncidentState;
import hauntedjvm.core.incident.Marks;
import hauntedjvm.core.incident.ScheduledPerturbation;
import hauntedjvm.core.random.Rng;
import hauntedjvm.core.random.Streams;
import hauntedjvm.core.state.WorldView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The procedural director.
 *
 * <p>Each tick it may intervene once. The chance grows with instability (itself a function of
 * how frightened, aware and corrupted the facility already is), so interventions feed back into
 * the conditions that produce more of them. The feedback is damped by the operator: people
 * gathered in lit rooms calm down, watched rooms resist vanishings, and killing the right
 * process ends a haunting. Whether a run reaches level 5 is not decided anywhere in this class.
 */
public final class AnomalySystem implements SimulationSystem {

    /** No interventions while the night shift settles in. */
    static final long GRACE_TICKS = 120;
    private static final double RARE_CHANCE = 0.00004;

    private final List<Perturbation> catalog;
    private final List<Perturbation> rare;
    private final Map<String, Perturbation> byName = new LinkedHashMap<>();

    public AnomalySystem() {
        this.catalog = List.of(
                new EnvironmentalPerturbations.DoorUnattended(),
                new EnvironmentalPerturbations.LightFlicker(),
                new EnvironmentalPerturbations.CameraInterference(),
                new EnvironmentalPerturbations.CameraLoop(),
                new EnvironmentalPerturbations.ObjectDisplacement(),
                new MindPerturbations.FalseMemory(),
                new MindPerturbations.MemoryErasure(),
                new MindPerturbations.BehaviorEcho(),
                new MindPerturbations.ObserverEffect(),
                new SystemPerturbations.ZombieProcess(),
                new SystemPerturbations.PidImpersonation(),
                new SystemPerturbations.OrphanMessage(),
                new SystemPerturbations.MisdatedRecord(),
                new IdentityPerturbations.BadgeGhost(),
                new IdentityPerturbations.Manifestation(),
                new IdentityPerturbations.DuplicateIdentity(),
                IdentityPerturbations.VANISHING);
        this.rare = RareEvents.all();
        for (Perturbation p : catalog) {
            byName.put(p.name(), p);
        }
        for (Perturbation p : rare) {
            byName.put(p.name(), p);
        }
    }

    @Override
    public String name() {
        return "anomaly";
    }

    /** Names of everything the director can do; used by tests and the debug overlay. */
    public List<String> repertoire() {
        return List.copyOf(byName.keySet());
    }

    @Override
    public void tick(TickContext ctx) {
        continueChains(ctx);
        retractShyRecords(ctx);
        rollRare(ctx);
        rollSpontaneous(ctx);
    }

    private void continueChains(TickContext ctx) {
        long t = ctx.tick();
        List<ScheduledPerturbation> due = new ArrayList<>();
        for (ScheduledPerturbation s : ctx.world().incident().scheduled()) {
            if (s.dueTick() <= t) {
                due.add(s);
            }
        }
        for (ScheduledPerturbation step : due) {
            Perturbation p = byName.get(step.perturbation());
            if (p == null) {
                throw new IllegalStateException("scheduled step for unknown perturbation " + step.perturbation());
            }
            ctx.emit(new PerturbationApplied(p.name(), p.category(), step.targets(), step.roomCode(), step.id(),
                    "step: " + step.detail()));
            p.resume(ctx, step, ctx.rng(Streams.ANOMALY_TARGET, step.id()));
        }
    }

    /** Anomalies marked shy withdraw their record as soon as the operator inspects them. */
    private void retractShyRecords(TickContext ctx) {
        WorldView w = ctx.world();
        IncidentState incident = w.incident();
        String ref = incident.lastInspected();
        Entity system = w.system();
        if (ref == null || system == null || incident.lastInspectedTick() < ctx.tick() - 1
                || !ref.startsWith("anomaly:")) {
            return;
        }
        String id = ref.substring("anomaly:".length());
        String mark = Marks.SHY_PREFIX + id;
        AnomalyRecord record = incident.anomaly(id).orElse(null);
        if (record != null && system.hasMark(mark) && record.status() != AnomalyRecord.Status.RETRACTED) {
            ctx.emit(new AnomalyRetracted(id, "RECORD WITHDRAWN"));
            ctx.emit(new MarkChanged(system.id(), mark, false));
        }
    }

    private void rollRare(TickContext ctx) {
        WorldView w = ctx.world();
        if (ctx.tick() < GRACE_TICKS * 3) {
            return;
        }
        double rate = RARE_CHANCE * ctx.config().rareEventRate();
        for (int i = 0; i < rare.size(); i++) {
            Perturbation p = rare.get(i);
            if (w.incident().level() < p.minLevel() || RareEvents.happened(w, p) || p.weight(w) <= 0) {
                continue;
            }
            Rng rng = ctx.rng(Streams.RARE, i);
            if (rng.chance(rate) && p.apply(ctx, rng)) {
                RareEvents.markHappened(ctx, p);
                return;
            }
        }
    }

    private void rollSpontaneous(TickContext ctx) {
        WorldView w = ctx.world();
        long t = ctx.tick();
        int level = w.incident().level();
        long last = w.incident().lastPerturbation();
        long gap = Math.max(25, 110 - 15L * level);
        if (t < GRACE_TICKS || (last >= 0 && t - last < gap)) {
            return;
        }
        double instability = Escalation.instability(w);
        double p = ctx.config().anomalyIntensity() * (0.006 + 0.004 * instability);
        Rng roll = ctx.rng(Streams.ANOMALY, 0);
        if (!roll.chance(p)) {
            return;
        }
        // Occasionally reach one level ahead: the facility foreshadows what it is becoming.
        int reach = level + (roll.chance(0.2) ? 1 : 0);
        List<Perturbation> eligible = catalog.stream().filter(c -> c.minLevel() <= reach).toList();
        Perturbation chosen = roll.weighted(eligible, c -> c.weight(w));
        if (chosen != null) {
            chosen.apply(ctx, ctx.rng(Streams.ANOMALY_TARGET, t));
        }
    }
}
