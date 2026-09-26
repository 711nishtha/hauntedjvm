package hauntedjvm.core.anomaly;

import hauntedjvm.core.engine.TickContext;
import hauntedjvm.core.incident.AnomalyCategory;
import hauntedjvm.core.incident.ScheduledPerturbation;
import hauntedjvm.core.random.Rng;
import hauntedjvm.core.state.WorldView;

/**
 * One thing the anomaly engine can do to the world.
 *
 * <p>A perturbation never produces an anomaly directly. It changes state (a memory, a badge, a
 * door, a process) and the consequences are what people witness and what the detector finds.
 * Preconditions are expressed through {@link #weight}: most perturbations need something
 * specific to exist first (a terminated process, a lone person, an unwatched room), which is
 * why the same seed can take very different paths depending on how the night has gone.
 */
public interface Perturbation {

    String name();

    AnomalyCategory category();

    /** Lowest escalation level at which this may happen spontaneously. */
    int minLevel();

    /** Relative likelihood given the current world; zero or less means not applicable now. */
    double weight(WorldView w);

    /**
     * Acts on the world. Implementations announce themselves with {@link Director#announce}
     * before their effects so the debug log shows cause before consequence.
     *
     * @return false if, on closer inspection, there was nothing suitable to act on
     */
    boolean apply(TickContext ctx, Rng rng);

    /** Continues a multi-step chain scheduled earlier by this perturbation. */
    default void resume(TickContext ctx, ScheduledPerturbation step, Rng rng) {
        throw new IllegalStateException(name() + " does not schedule follow-up steps");
    }
}
