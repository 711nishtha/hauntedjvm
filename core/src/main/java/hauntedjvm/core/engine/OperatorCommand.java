package hauntedjvm.core.engine;

import hauntedjvm.core.entity.EntityId;
import hauntedjvm.core.entity.LightMode;
import hauntedjvm.core.incident.Directive;

/**
 * Input from the operator. Commands are validated and translated into operator events by the
 * engine on the simulation thread, so they are ordered with respect to ticks and replay exactly.
 */
public sealed interface OperatorCommand {

    record SwitchCamera(String cameraCode) implements OperatorCommand {
    }

    record SetLight(String roomCode, LightMode mode) implements OperatorCommand {
    }

    record SetDoorLock(String doorCode, boolean locked) implements OperatorCommand {
    }

    record TerminateProcess(EntityId process) implements OperatorCommand {
    }

    record RestartProcess(EntityId process) implements OperatorCommand {
    }

    record IssueDirective(Directive.Type type, String roomCode) implements OperatorCommand {
    }

    record Inspect(String ref) implements OperatorCommand {
    }

    record MarkPaused(boolean paused) implements OperatorCommand {
    }

    /** Outcome shown back to the operator. */
    record Result(boolean accepted, String message) {
        public static Result ok(String message) {
            return new Result(true, message);
        }

        public static Result rejected(String message) {
            return new Result(false, message);
        }
    }
}
