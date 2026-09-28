package hauntedjvm.app.ui;

import hauntedjvm.app.runtime.Frame;
import hauntedjvm.core.state.WorldView;

/**
 * What the interface is showing this frame.
 *
 * @param world the world on screen: the live facility, or the recording at the playhead
 * @param live  the live facility, always; operator controls act on this one
 */
record Display(WorldView world, WorldView live, Frame frame, boolean review) {

    long tick() {
        return world.tick();
    }
}
