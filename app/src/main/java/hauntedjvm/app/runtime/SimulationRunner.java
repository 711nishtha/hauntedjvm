package hauntedjvm.app.runtime;

import hauntedjvm.core.config.SimulationConfig;
import hauntedjvm.core.engine.OperatorCommand;
import hauntedjvm.core.engine.SimulationEngine;
import hauntedjvm.core.timeline.Timeline;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns a {@link SimulationEngine} on one dedicated thread and paces it in real time.
 *
 * <p>The engine is only ever touched by that thread. Everything else talks to it through a
 * bounded mailbox and reads what it publishes through an atomic reference, so there are no locks
 * shared with the UI and a slow renderer can never stall the simulation (or the reverse).
 * If the mailbox is full, commands are refused immediately rather than blocking the caller:
 * backpressure is surfaced to the operator as a rejected command.
 *
 * <p>Pacing uses a fixed timestep with bounded catch-up. When the machine cannot keep up with
 * the requested speed the runner drops the backlog instead of spiralling, and the measured
 * rate in the status tells the operator so.
 */
public final class SimulationRunner implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SimulationRunner.class);
    private static final int MAILBOX = 256;
    private static final int MAX_CATCH_UP = 16;
    private static final long IDLE_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    /** Consistent view of the recording for saving: taken on the simulation thread between ticks. */
    public record Cut(SimulationConfig config, Timeline timeline, long headTick, long eventCount) {
    }

    private sealed interface Request {
    }

    private record Command(OperatorCommand command, CompletableFuture<OperatorCommand.Result> reply)
            implements Request {
    }

    private record SetPaused(boolean paused) implements Request {
    }

    private record Step(int ticks) implements Request {
    }

    private record SetSpeed(Speed speed) implements Request {
    }

    private record Rewind(long tick, CompletableFuture<Boolean> reply) implements Request {
    }

    private record Capture(CompletableFuture<Cut> reply) implements Request {
    }

    private final SimulationEngine engine;
    private final double baseTicksPerSecond;
    private final BlockingQueue<Request> mailbox = new ArrayBlockingQueue<>(MAILBOX);
    private final AtomicReference<Frame> latest = new AtomicReference<>();
    private final Thread thread;
    private volatile boolean running = true;

    private Speed speed;
    private boolean paused;
    private String error;
    private double measuredTps;
    private long windowStart = System.nanoTime();
    private long windowTicks;

    public SimulationRunner(SimulationEngine engine, Speed initialSpeed, double baseTicksPerSecond, boolean startPaused) {
        this.engine = engine;
        this.speed = initialSpeed;
        this.baseTicksPerSecond = baseTicksPerSecond;
        this.paused = startPaused;
        publish();
        this.thread = Thread.ofPlatform().name("hauntedjvm-sim").daemon().start(this::loop);
    }

    public Frame latest() {
        return latest.get();
    }

    public SimulationConfig config() {
        return engine.config();
    }

    public CompletableFuture<OperatorCommand.Result> submit(OperatorCommand command) {
        CompletableFuture<OperatorCommand.Result> reply = new CompletableFuture<>();
        if (!mailbox.offer(new Command(command, reply))) {
            reply.complete(OperatorCommand.Result.rejected("command buffer full; the facility is not listening"));
        }
        return reply;
    }

    public void setPaused(boolean value) {
        offer(new SetPaused(value));
    }

    public void step(int ticks) {
        offer(new Step(ticks));
    }

    public void setSpeed(Speed value) {
        offer(new SetSpeed(value));
    }

    /** Discards the recording after {@code tick} and continues live from there. */
    public CompletableFuture<Boolean> rewindTo(long tick) {
        CompletableFuture<Boolean> reply = new CompletableFuture<>();
        if (!mailbox.offer(new Rewind(tick, reply))) {
            reply.complete(false);
        }
        return reply;
    }

    public CompletableFuture<Cut> capture() {
        CompletableFuture<Cut> reply = new CompletableFuture<>();
        if (!mailbox.offer(new Capture(reply))) {
            reply.completeExceptionally(new IllegalStateException("simulation busy"));
        }
        return reply;
    }

    private void offer(Request r) {
        if (!mailbox.offer(r)) {
            LOG.atWarn().addKeyValue("request", r).log("mailbox full; request dropped");
        }
    }

    private void loop() {
        long next = System.nanoTime();
        while (running) {
            try {
                long period = periodNanos();
                long wait = paused || error != null ? IDLE_POLL_NANOS : Math.max(0, next - System.nanoTime());
                Request request = mailbox.poll(wait, TimeUnit.NANOSECONDS);
                if (request != null) {
                    boolean wasPaused = paused;
                    handle(request);
                    Request more;
                    while ((more = mailbox.poll()) != null) {
                        handle(more);
                    }
                    publish();
                    if (wasPaused && !paused) {
                        next = System.nanoTime();
                    }
                    continue;
                }
                if (paused || error != null) {
                    continue;
                }
                int steps = 0;
                while (System.nanoTime() >= next && steps < MAX_CATCH_UP) {
                    tick();
                    steps++;
                    next += period;
                }
                if (System.nanoTime() - next > period * MAX_CATCH_UP) {
                    // Hopelessly behind: drop the backlog instead of spiralling.
                    next = System.nanoTime() + period;
                }
                if (steps > 0) {
                    publish();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
                LOG.atError().setCause(e).addKeyValue("tick", engine.tick()).log("simulation halted");
                publish();
            }
        }
    }

    private void tick() {
        engine.step();
        windowTicks++;
        long now = System.nanoTime();
        if (now - windowStart >= 1_000_000_000L) {
            measuredTps = windowTicks * 1e9 / (now - windowStart);
            windowTicks = 0;
            windowStart = now;
        }
    }

    private void handle(Request request) {
        switch (request) {
            case Command c -> {
                try {
                    c.reply().complete(engine.execute(c.command()));
                } catch (RuntimeException e) {
                    c.reply().completeExceptionally(e);
                }
            }
            case SetPaused p -> {
                if (p.paused() != paused) {
                    paused = p.paused();
                    engine.execute(new OperatorCommand.MarkPaused(paused));
                }
            }
            case Step s -> {
                for (int i = 0; i < s.ticks() && error == null; i++) {
                    tick();
                }
            }
            case SetSpeed s -> speed = s.speed();
            case Rewind r -> {
                if (r.tick() >= 0 && r.tick() < engine.tick()) {
                    engine.rewindTo(r.tick());
                    LOG.atInfo().addKeyValue("toTick", r.tick()).log("timeline rewound");
                    r.reply().complete(true);
                } else {
                    r.reply().complete(false);
                }
            }
            case Capture c -> c.reply().complete(new Cut(engine.config(), engine.timeline(), engine.tick(),
                    engine.timeline().log().size()));
        }
    }

    private long periodNanos() {
        return (long) (1e9 / (baseTicksPerSecond * speed.factor()));
    }

    private void publish() {
        Frame.Status status = new Frame.Status(engine.tick(), speed, paused, paused ? 0 : measuredTps,
                engine.lastStepNanos() / 1_000.0, baseTicksPerSecond * speed.factor(), error);
        latest.set(new Frame(engine.snapshot(), engine.timeline(), engine.timeline().log().size(), System.nanoTime(),
                status));
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
        try {
            thread.join(1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
