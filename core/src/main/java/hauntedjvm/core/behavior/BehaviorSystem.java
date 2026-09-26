package hauntedjvm.core.behavior;

import hauntedjvm.core.engine.SimulationSystem;
import hauntedjvm.core.engine.TickContext;
import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityKind;
import hauntedjvm.core.entity.Goal;
import hauntedjvm.core.entity.StateMachine;
import hauntedjvm.core.event.EntityEvent.EntityStateChanged;
import hauntedjvm.core.event.EntityEvent.GoalChanged;
import hauntedjvm.core.state.WorldView;
import java.util.Objects;

/**
 * Decide, plan, move: for every mind, every tick.
 *
 * <p>All minds share one thread and are processed in serial order. A thread (even a virtual
 * one) per entity would make the order of decisions depend on the scheduler and destroy
 * reproducibility, for no gain: a tick for hundreds of minds takes well under a millisecond.
 */
public final class BehaviorSystem implements SimulationSystem {

    @Override
    public String name() {
        return "behavior";
    }

    @Override
    public void tick(TickContext ctx) {
        WorldView w = ctx.world();
        for (Entity person : w.ofKind(EntityKind.PERSON)) {
            if (person.present()) {
                person(ctx, person);
            }
        }
        for (Entity unknown : w.ofKind(EntityKind.UNKNOWN)) {
            if (unknown.present()) {
                UnknownBrain.think(ctx, unknown);
            }
        }
    }

    private void person(TickContext ctx, Entity person) {
        WorldView w = ctx.world();
        PersonBrain.Decision decision = PersonBrain.decide(w, person);
        boolean changed = false;
        if (decision.state() != person.state()
                && StateMachine.canTransition(EntityKind.PERSON, person.state(), decision.state())) {
            ctx.emit(new EntityStateChanged(person.id(), person.state(), decision.state(), decision.reason()));
            person = w.entity(person.id());
            changed = true;
        }
        if (changed || GoalPlanner.needsNewGoal(ctx, person)) {
            Goal next = GoalPlanner.plan(ctx, person);
            if (next != null && !sameIntent(next, person.mind().goal(), ctx.tick())) {
                ctx.emit(new GoalChanged(person.id(), next));
                person = w.entity(person.id());
            }
        }
        if (!Locomotion.heldByObservation(w, person)) {
            Locomotion.advance(ctx, person, Locomotion.pace(person.state()));
        }
    }

    /** Refreshing a goal to the same place for the same reason is not worth an event. */
    private static boolean sameIntent(Goal a, Goal b, long tick) {
        return b != null && b.expiresAt() > tick && a.cell().equals(b.cell()) && a.reason() == b.reason()
                && Objects.equals(a.target(), b.target()) && Math.abs(a.expiresAt() - b.expiresAt()) < 30;
    }
}
