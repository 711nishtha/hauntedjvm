package hauntedjvm.app.ui;

import hauntedjvm.core.engine.OperatorCommand;
import hauntedjvm.core.entity.EntityId;

/** What panels may ask the workstation to do on the operator's behalf. */
interface Operator {

    void select(Selection selection);

    /** Moves the playhead to a tick (entering review), optionally highlighting an event. */
    void jumpTo(long tick, long seq);

    /** Sends a command to the live facility; the outcome is shown as a toast. */
    void command(OperatorCommand command);

    void view(Feed feed);

    void follow(EntityId id);

    void toggleTrace(EntityId id);

    void writeNote(String targetRef);

    boolean reviewing();
}
