package hauntedjvm.app.ui;

import hauntedjvm.app.runtime.Speed;
import hauntedjvm.core.entity.EntityId;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableSet;

/**
 * Interface state shared by every panel. JavaFX-thread only.
 *
 * <p>{@code reviewTick} is the playhead: -1 means the monitors show the live facility; anything
 * else means they show the recording at that tick, reconstructed from the timeline.
 */
public final class ViewState {

    public static final long LIVE = -1;

    private final ObjectProperty<Feed> feed = new SimpleObjectProperty<>(new Feed.Camera("CAM-01"));
    private final ObjectProperty<Selection> selection = new SimpleObjectProperty<>(new Selection.None());
    private final LongProperty reviewTick = new SimpleLongProperty(LIVE);
    private final BooleanProperty playing = new SimpleBooleanProperty(false);
    private final BooleanProperty follow = new SimpleBooleanProperty(false);
    private final ObjectProperty<Speed> reviewSpeed = new SimpleObjectProperty<>(Speed.DOUBLE);
    private final ObservableSet<EntityId> traced = FXCollections.observableSet();
    private final boolean debug;

    public ViewState(boolean debug) {
        this.debug = debug;
    }

    public ObjectProperty<Feed> feed() {
        return feed;
    }

    public ObjectProperty<Selection> selection() {
        return selection;
    }

    public LongProperty reviewTick() {
        return reviewTick;
    }

    public BooleanProperty playing() {
        return playing;
    }

    public BooleanProperty follow() {
        return follow;
    }

    public ObjectProperty<Speed> reviewSpeed() {
        return reviewSpeed;
    }

    public ObservableSet<EntityId> traced() {
        return traced;
    }

    public boolean reviewing() {
        return reviewTick.get() != LIVE;
    }

    public boolean debug() {
        return debug;
    }

    public void select(Selection s) {
        selection.set(s);
    }

    public void goLive() {
        playing.set(false);
        reviewTick.set(LIVE);
    }
}
