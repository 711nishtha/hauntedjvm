package hauntedjvm.core.behavior;

import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityId;
import hauntedjvm.core.entity.LightMode;
import hauntedjvm.core.entity.MemoryKind;
import hauntedjvm.core.entity.MemoryTrace;
import hauntedjvm.core.entity.Mind;
import hauntedjvm.core.state.WorldView;

/** Pure helpers for what a mind can see and already knows. */
public final class Perception {

    private Perception() {
    }

    /**
     * Effective illumination of a room at a tick. Flicker is a deterministic function of time and
     * room, so the renderer and the simulation agree on which ticks are dark.
     */
    public static double light(WorldView w, String roomCode, long tick) {
        LightMode mode = w.light(roomCode);
        if (mode == LightMode.FLICKER) {
            return flickerDark(roomCode, tick) ? 0.1 : 0.85;
        }
        return mode.level();
    }

    public static boolean flickerDark(String roomCode, long tick) {
        long h = tick * 0x9E3779B97F4A7C15L + roomCode.hashCode();
        return Long.remainderUnsigned(h ^ (h >>> 29), 3) == 0;
    }

    /** Whether the mind has seen {@code subject} within {@code window} ticks. */
    public static boolean recentlySaw(Mind mind, EntityId subject, long tick, long window) {
        for (MemoryTrace m : mind.memories()) {
            if (subject.equals(m.subject()) && m.tick() >= tick - window
                    && (m.kind() == MemoryKind.SAW_ENTITY || m.kind() == MemoryKind.SAW_ANOMALY)) {
                return true;
            }
        }
        return false;
    }

    /** The most recent remembered location of an object, or {@code null}. */
    public static MemoryTrace lastKnownLocation(Mind mind, EntityId object) {
        MemoryTrace best = null;
        for (MemoryTrace m : mind.memories()) {
            if (m.kind() == MemoryKind.OBJECT_LOCATION && object.equals(m.subject())
                    && (best == null || m.tick() > best.tick())) {
                best = m;
            }
        }
        return best;
    }

    public static int personsIn(WorldView w, String roomCode) {
        int n = 0;
        for (Entity e : w.occupants(roomCode)) {
            if (e.kind() == hauntedjvm.core.entity.EntityKind.PERSON) {
                n++;
            }
        }
        return n;
    }
}
