package hauntedjvm.core.engine;

/**
 * One stage of the tick pipeline.
 *
 * <p>Systems must be stateless with respect to the simulation: anything that influences a
 * future decision has to live in world state (and therefore in events), never in a field of
 * the system. A pure memo such as the navigator's flow-field cache is fine. This rule is what
 * lets an engine resumed from a checkpoint behave exactly like one that never stopped, and it is
 * enforced by the resume-determinism tests.
 */
public interface SimulationSystem {

    String name();

    void tick(TickContext ctx);
}
