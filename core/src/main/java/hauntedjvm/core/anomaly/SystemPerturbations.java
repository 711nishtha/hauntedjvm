package hauntedjvm.core.anomaly;

import hauntedjvm.core.behavior.Reactions;
import hauntedjvm.core.engine.TickContext;
import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityId;
import hauntedjvm.core.entity.EntityKind;
import hauntedjvm.core.entity.EntityState;
import hauntedjvm.core.entity.Goal;
import hauntedjvm.core.entity.ProcessFacet;
import hauntedjvm.core.event.CommunicationEvent.Channel;
import hauntedjvm.core.event.CommunicationEvent.MessageSent;
import hauntedjvm.core.event.EntityEvent.EntityObserved;
import hauntedjvm.core.event.EntityEvent.GoalChanged;
import hauntedjvm.core.event.EntityEvent.MarkChanged;
import hauntedjvm.core.event.EventRecord;
import hauntedjvm.core.event.ProcessEvent.PidClaimed;
import hauntedjvm.core.event.ProcessEvent.ProcessStopped;
import hauntedjvm.core.incident.AnomalyCategory;
import hauntedjvm.core.incident.Marks;
import hauntedjvm.core.incident.ScheduledPerturbation;
import hauntedjvm.core.random.Rng;
import hauntedjvm.core.state.WorldView;
import hauntedjvm.core.world.RoomLayout;
import java.util.List;

/** Processes, messages and records misbehaving. */
final class SystemPerturbations {

    private SystemPerturbations() {
    }

    /** A process dies but keeps allocating. The watchdog, fooled by the activity, never restarts it. */
    static final class ZombieProcess implements Perturbation {
        @Override
        public String name() {
            return "zombie-process";
        }

        @Override
        public AnomalyCategory category() {
            return AnomalyCategory.SYSTEM;
        }

        @Override
        public int minLevel() {
            return 1;
        }

        @Override
        public double weight(WorldView w) {
            return w.ofKind(EntityKind.PROCESS).stream().anyMatch(p -> !p.hasMark(Marks.LINGERING)) ? 0.7 : 0;
        }

        @Override
        public boolean apply(TickContext ctx, Rng rng) {
            List<Entity> candidates = ctx.world().ofKind(EntityKind.PROCESS).stream()
                    .filter(p -> !p.hasMark(Marks.LINGERING) && !((ProcessFacet) p.facet()).command().equals("watchdog"))
                    .toList();
            if (candidates.isEmpty()) {
                return false;
            }
            Entity p = rng.pick(candidates);
            ProcessFacet pf = (ProcessFacet) p.facet();
            Director.announce(ctx, this, List.of(p.id()), null, pf.command());
            if (p.state() == EntityState.RUNNING) {
                ctx.emit(new ProcessStopped(p.id(), pf.pid(), "SIGSEGV"));
            }
            ctx.emit(new MarkChanged(p.id(), Marks.LINGERING, true));
            return true;
        }
    }

    /** One process starts answering to another's pid. */
    static final class PidImpersonation implements Perturbation {
        @Override
        public String name() {
            return "pid-impersonation";
        }

        @Override
        public AnomalyCategory category() {
            return AnomalyCategory.IDENTITY;
        }

        @Override
        public int minLevel() {
            return 2;
        }

        @Override
        public double weight(WorldView w) {
            return 0.5;
        }

        @Override
        public boolean apply(TickContext ctx, Rng rng) {
            List<Entity> processes = ctx.world().ofKind(EntityKind.PROCESS);
            Entity a = rng.pick(processes);
            Entity b = rng.pick(processes);
            ProcessFacet af = (ProcessFacet) a.facet();
            ProcessFacet bf = (ProcessFacet) b.facet();
            if (a.id().equals(b.id()) || af.claimedPid() != af.pid()) {
                return false;
            }
            Director.announce(ctx, this, List.of(a.id(), b.id()), null, af.command() + " as " + bf.pid());
            ctx.emit(new PidClaimed(a.id(), bf.pid()));
            Director.schedule(ctx, this, 200 + rng.nextInt(400), List.of(a.id()), null, "restore");
            return true;
        }

        @Override
        public void resume(TickContext ctx, ScheduledPerturbation step, Rng rng) {
            Entity a = ctx.world().entity(step.targets().getFirst());
            if (a != null && a.facet() instanceof ProcessFacet pf && pf.claimedPid() != pf.pid()) {
                ctx.emit(new PidClaimed(a.id(), pf.pid()));
            }
        }
    }

    /**
     * A message with no sender on a terminal, the intercom or the radio. Some of them are
     * predictions, and the engine sees to it that predictions have a way of coming true.
     */
    static final class OrphanMessage implements Perturbation {
        @Override
        public String name() {
            return "orphan-message";
        }

        @Override
        public AnomalyCategory category() {
            return AnomalyCategory.COMMUNICATION;
        }

        @Override
        public int minLevel() {
            return 1;
        }

        @Override
        public double weight(WorldView w) {
            return 1.0 + 0.2 * w.incident().level();
        }

        @Override
        public boolean apply(TickContext ctx, Rng rng) {
            WorldView w = ctx.world();
            MessageCorpus.Line line = MessageCorpus.compose(w, rng);
            Channel channel = rng.pick(List.of(Channel.TERMINAL, Channel.INTERCOM, Channel.RADIO));
            Director.announce(ctx, this, List.of(), null, channel + ": " + line.text());
            EventRecord msg = ctx.emit(new MessageSent(null, null, channel, line.text(), null));
            List<Entity> listeners = Director.presentPersons(w);
            int heard = 0;
            for (Entity p : listeners) {
                boolean reaches = switch (channel) {
                    case INTERCOM -> true;
                    case RADIO -> p.mind().traits().obedience() > 0.5;
                    default -> {
                        RoomLayout room = w.map().roomAt(p.position());
                        yield room != null && !room.terminals().isEmpty();
                    }
                };
                if (reaches && heard < 6) {
                    Reactions.witnessAnomaly(ctx, p, msg.seq(), w.roomOf(p), 0.35, channel.name().toLowerCase()
                            + ": \"" + line.text() + "\"");
                    heard++;
                }
            }
            if (line.prophecySubject() != null) {
                Director.schedule(ctx, this, 40 + rng.nextInt(80), List.of(line.prophecySubject().id()),
                        line.prophecyRoom(), "prophecy");
            }
            return true;
        }

        @Override
        public void resume(TickContext ctx, ScheduledPerturbation step, Rng rng) {
            WorldView w = ctx.world();
            Entity p = w.entity(step.targets().getFirst());
            if (p == null || !p.present() || !p.hasMind()) {
                return;
            }
            RoomLayout room = w.map().room(step.roomCode());
            var cell = rng.pick(room.anchors());
            if (ctx.navigator().reachable(p.position(), cell, w.blockedDoors())) {
                ctx.emit(new GoalChanged(p.id(), new Goal(cell, room.code(), Goal.Reason.DRAWN, null,
                        ctx.tick() + 200)));
            }
        }
    }

    /** A record that claims to have been written at a time it was not. */
    static final class MisdatedRecord implements Perturbation {
        @Override
        public String name() {
            return "misdated-record";
        }

        @Override
        public AnomalyCategory category() {
            return AnomalyCategory.TEMPORAL;
        }

        @Override
        public int minLevel() {
            return 2;
        }

        @Override
        public double weight(WorldView w) {
            return Director.presentPersons(w).size() >= 2 ? 0.6 : 0;
        }

        @Override
        public boolean apply(TickContext ctx, Rng rng) {
            WorldView w = ctx.world();
            List<Entity> persons = Director.presentPersons(w);
            Entity observer = rng.pick(persons);
            Entity subject = rng.pick(persons);
            if (observer.id().equals(subject.id())) {
                return false;
            }
            Director.announce(ctx, this, List.of(observer.id(), subject.id()), w.roomOf(subject), "misdated");
            if (rng.chance(0.5)) {
                // Observed before the facility's records begin.
                ctx.emitDated(new EntityObserved(observer.id(), subject.id(), w.roomOf(subject)),
                        -(600 + rng.nextInt(80_000)));
            } else {
                Entity log = w.process("incident-log");
                EntityId sender = log == null ? null : log.id();
                ctx.emitDated(new MessageSent(sender, null, Channel.INCIDENT_LOG,
                        "INCIDENT CLOSED. NO FURTHER ENTRIES.", null), ctx.tick() + 900 + rng.nextInt(6000));
            }
            return true;
        }
    }
}
