package hauntedjvm.core.entity;

import hauntedjvm.core.world.Cell;

/**
 * Where a mind is heading and why.
 *
 * @param target    the entity being followed, stalked or echoed, if any
 * @param expiresAt tick after which the behaviour system reconsiders; includes travel and dwell time
 */
public record Goal(Cell cell, String roomCode, Reason reason, EntityId target, long expiresAt) {

    public enum Reason {
        ROUTINE,
        INVESTIGATE,
        FLEE,
        AVOID,
        FOLLOW,
        GATHER,
        ECHO,
        DRAWN,
        WANDER,
        STALK,
        IMPERSONATE
    }
}
