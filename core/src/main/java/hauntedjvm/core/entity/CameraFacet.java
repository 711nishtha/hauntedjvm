package hauntedjvm.core.entity;

import hauntedjvm.core.world.Cell;

/**
 * @param timelineOffset while {@link CameraStatus#LOOPING}, how many ticks behind the feed is
 * @param statusUntil    tick at which a transient status reverts to online; -1 if permanent
 */
public record CameraFacet(
        String code,
        String roomCode,
        Cell mount,
        CameraStatus status,
        int timelineOffset,
        long statusUntil) implements Facet {

    public CameraFacet withStatus(CameraStatus newStatus, int offset, long until) {
        return new CameraFacet(code, roomCode, mount, newStatus, offset, until);
    }
}
