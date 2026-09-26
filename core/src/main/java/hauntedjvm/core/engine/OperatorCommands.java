package hauntedjvm.core.engine;

import hauntedjvm.core.entity.CameraFacet;
import hauntedjvm.core.entity.DoorFacet;
import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityState;
import hauntedjvm.core.entity.LightMode;
import hauntedjvm.core.entity.ProcessFacet;
import hauntedjvm.core.entity.RoomFacet;
import hauntedjvm.core.event.EnvironmentEvent.DoorLockChanged;
import hauntedjvm.core.event.EnvironmentEvent.LightChanged;
import hauntedjvm.core.event.OperatorEvent;
import hauntedjvm.core.event.ProcessEvent.ProcessStarted;
import hauntedjvm.core.event.ProcessEvent.ProcessStopped;
import hauntedjvm.core.incident.Directive;
import hauntedjvm.core.state.WorldView;
import hauntedjvm.core.world.DoorLayout;

/** Validation and translation of operator commands into operator events. */
final class OperatorCommands {

    static final String OPERATOR = "OPERATOR";
    /** How long an intercom directive stays in force. */
    static final int DIRECTIVE_TICKS = 240;

    private OperatorCommands() {
    }

    static OperatorCommand.Result execute(TickContext ctx, OperatorCommand command) {
        WorldView w = ctx.world();
        return switch (command) {
            case OperatorCommand.SwitchCamera c -> switchCamera(ctx, w, c);
            case OperatorCommand.SetLight c -> setLight(ctx, w, c);
            case OperatorCommand.SetDoorLock c -> setDoorLock(ctx, w, c);
            case OperatorCommand.TerminateProcess c -> {
                Entity p = w.entity(c.process());
                if (p == null || !(p.facet() instanceof ProcessFacet pf)) {
                    yield OperatorCommand.Result.rejected("no such process");
                }
                if (p.state() == EntityState.TERMINATED) {
                    yield OperatorCommand.Result.rejected(pf.command() + " is not running");
                }
                ctx.emit(new ProcessStopped(p.id(), pf.pid(), OPERATOR + ":SIGTERM"));
                yield OperatorCommand.Result.ok("SIGTERM sent to " + pf.command() + " (pid " + pf.pid() + ")");
            }
            case OperatorCommand.RestartProcess c -> {
                Entity p = w.entity(c.process());
                if (p == null || !(p.facet() instanceof ProcessFacet pf)) {
                    yield OperatorCommand.Result.rejected("no such process");
                }
                if (p.state() != EntityState.TERMINATED) {
                    yield OperatorCommand.Result.rejected(pf.command() + " is already running");
                }
                ctx.emit(new ProcessStarted(p.id(), pf.pid()));
                yield OperatorCommand.Result.ok(pf.command() + " restarted");
            }
            case OperatorCommand.IssueDirective c -> {
                if (c.type() == Directive.Type.GATHER && (c.roomCode() == null || !listedRoom(w, c.roomCode()))) {
                    yield OperatorCommand.Result.rejected("gather needs a listed room");
                }
                Directive d = new Directive(c.type(), c.roomCode(), ctx.tick(), ctx.tick() + DIRECTIVE_TICKS);
                ctx.emit(new OperatorEvent.DirectiveIssued(d));
                yield OperatorCommand.Result.ok("intercom: " + describe(d));
            }
            case OperatorCommand.Inspect c -> {
                ctx.emit(new OperatorEvent.OperatorInspected(c.ref()));
                yield OperatorCommand.Result.ok("inspecting " + c.ref());
            }
            case OperatorCommand.MarkPaused c -> {
                ctx.emit(c.paused() ? new OperatorEvent.SimulationPaused() : new OperatorEvent.SimulationResumed());
                yield OperatorCommand.Result.ok(c.paused() ? "paused" : "resumed");
            }
        };
    }

    private static OperatorCommand.Result switchCamera(TickContext ctx, WorldView w, OperatorCommand.SwitchCamera c) {
        String current = w.incident().observedCamera();
        if (c.cameraCode() != null && (w.camera(c.cameraCode()) == null
                || !(w.camera(c.cameraCode()).facet() instanceof CameraFacet))) {
            return OperatorCommand.Result.rejected("no such camera: " + c.cameraCode());
        }
        if (java.util.Objects.equals(current, c.cameraCode())) {
            return OperatorCommand.Result.ok("already on " + c.cameraCode());
        }
        ctx.emit(new OperatorEvent.CameraChanged(current, c.cameraCode()));
        return OperatorCommand.Result.ok(c.cameraCode() == null ? "cameras released" : "switched to " + c.cameraCode());
    }

    private static OperatorCommand.Result setLight(TickContext ctx, WorldView w, OperatorCommand.SetLight c) {
        Entity room = w.room(c.roomCode());
        if (room == null || !(room.facet() instanceof RoomFacet rf) || !rf.listed()) {
            return OperatorCommand.Result.rejected("no such room: " + c.roomCode());
        }
        if (c.mode() == LightMode.EMERGENCY || c.mode() == LightMode.FLICKER) {
            return OperatorCommand.Result.rejected("lighting panel only offers ON, DIM and OFF");
        }
        if (!w.lightingOnline()) {
            return OperatorCommand.Result.rejected("lumen-ctl is not running");
        }
        ctx.emit(new LightChanged(room.id(), c.mode(), -1, OPERATOR));
        return OperatorCommand.Result.ok(rf.code() + " lights " + c.mode());
    }

    private static OperatorCommand.Result setDoorLock(TickContext ctx, WorldView w, OperatorCommand.SetDoorLock c) {
        DoorLayout layout = w.map().findDoor(c.doorCode()).orElse(null);
        if (layout == null) {
            return OperatorCommand.Result.rejected("no such door: " + c.doorCode());
        }
        Entity door = w.doorAt(layout.cell());
        if (door == null || !(door.facet() instanceof DoorFacet df) || df.sealed()) {
            return OperatorCommand.Result.rejected(c.doorCode() + " does not respond");
        }
        if (df.locked() == c.locked()) {
            return OperatorCommand.Result.ok(c.doorCode() + " already " + (c.locked() ? "locked" : "unlocked"));
        }
        ctx.emit(new DoorLockChanged(door.id(), c.locked(), OPERATOR));
        return OperatorCommand.Result.ok(c.doorCode() + (c.locked() ? " locked" : " unlocked"));
    }

    private static boolean listedRoom(WorldView w, String code) {
        Entity room = w.room(code);
        return room != null && room.facet() instanceof RoomFacet rf && rf.listed();
    }

    static String describe(Directive d) {
        return switch (d.type()) {
            case GATHER -> "all staff to " + d.roomCode();
            case RETURN_TO_ROUTINE -> "all staff return to stations";
        };
    }
}
