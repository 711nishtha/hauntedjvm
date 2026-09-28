package hauntedjvm.app.render;

import hauntedjvm.core.entity.EntityId;

/** A clickable screen region produced by a renderer. Later hits are drawn on top. */
public record Hit(EntityId id, double x, double y, double w, double h) {

    public boolean contains(double px, double py) {
        return px >= x && px <= x + w && py >= y && py <= y + h;
    }
}
