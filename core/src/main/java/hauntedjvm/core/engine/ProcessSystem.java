package hauntedjvm.core.engine;

import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityKind;
import hauntedjvm.core.entity.EntityState;
import hauntedjvm.core.entity.ProcessFacet;
import hauntedjvm.core.event.ProcessEvent.MemoryAllocated;
import hauntedjvm.core.event.ProcessEvent.MemoryReleased;
import hauntedjvm.core.event.ProcessEvent.ProcessStarted;
import hauntedjvm.core.incident.Marks;
import hauntedjvm.core.random.Rng;
import hauntedjvm.core.random.Streams;
import hauntedjvm.core.state.WorldView;

/**
 * The facility's fictional processes: they allocate, release, die and get restarted by the
 * watchdog. A process marked lingering keeps allocating after it has been terminated, which the
 * detector reports as a process that continues to exist after its own death.
 */
public final class ProcessSystem implements SimulationSystem {

    private static final long WATCHDOG_DELAY = 60;
    private static final long OPERATOR_KILL_DELAY = 180;

    @Override
    public String name() {
        return "processes";
    }

    @Override
    public void tick(TickContext ctx) {
        WorldView w = ctx.world();
        long t = ctx.tick();
        Entity watchdog = w.process("watchdog");
        boolean watchdogAlive = watchdog != null && watchdog.state() == EntityState.RUNNING;
        for (Entity p : w.ofKind(EntityKind.PROCESS)) {
            if (!(p.facet() instanceof ProcessFacet f)) {
                continue;
            }
            boolean alive = p.state() == EntityState.RUNNING;
            if (alive || p.hasMark(Marks.LINGERING)) {
                churn(ctx, p, f, alive);
            }
            if (!alive && watchdogAlive && !p.id().equals(watchdog.id())) {
                long delay = f.stopReason() != null && f.stopReason().startsWith("OPERATOR")
                        ? OPERATOR_KILL_DELAY : WATCHDOG_DELAY;
                // The watchdog judges liveness by activity. A dead process that is still
                // allocating looks alive to it, so it never gets restarted.
                if (t - f.terminatedTick() >= delay && t - f.lastActivity() >= delay) {
                    ctx.emit(new ProcessStarted(p.id(), f.pid()));
                }
            }
        }
    }

    private void churn(TickContext ctx, Entity p, ProcessFacet f, boolean alive) {
        Rng rng = ctx.rng(Streams.PROCESS, p.id().value());
        if (!rng.chance(alive ? 0.035 : 0.05)) {
            return;
        }
        long budget = (48L + (f.pid() % 64)) << 20;
        boolean release = f.heapBytes() > budget * 0.85 || (f.heapBytes() > (8L << 20) && rng.chance(0.35));
        if (release) {
            long bytes = Math.max(4096, (long) (f.heapBytes() * rng.nextDouble(0.1, 0.33)));
            ctx.emit(new MemoryReleased(p.id(), bytes));
        } else {
            long bytes = (64L << 10) + rng.nextInt(3 << 20);
            ctx.emit(new MemoryAllocated(p.id(), bytes, alive ? label(f.command()) : "unowned"));
        }
    }

    private static String label(String command) {
        return switch (command) {
            case "cam-mux" -> "frame-buffer";
            case "badge-trackd" -> "badge-index";
            case "tape-indexer" -> "tape-block";
            case "incident-log" -> "journal";
            case "lumen-ctl" -> "dimmer-table";
            case "archive-sync" -> "sync-chunk";
            default -> "heap";
        };
    }
}
