package hauntedjvm.app.render;

import hauntedjvm.core.entity.EntityId;
import hauntedjvm.core.state.WorldView;
import java.util.Set;

/**
 * Everything a renderer needs for one frame.
 *
 * @param alpha    progress from the previous tick to the current one, for interpolation
 * @param review   true when showing recorded history rather than the live facility
 * @param traced   entities whose relationships are drawn on the floor plan
 */
public record RenderInput(WorldView world, Motion motion, double alpha, long nanos, EntityId selected, boolean review,
                          boolean debug, Set<EntityId> traced) {

    public RenderInput {
        traced = Set.copyOf(traced);
    }
}
