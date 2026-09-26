package hauntedjvm.core.entity;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Legal states and transitions per entity kind.
 *
 * <p>Behaviour code consults this before emitting a state change. The event applier does not:
 * the log is the source of truth, and some anomalies deliberately record transitions that
 * should be impossible. The anomaly detector reports those instead of the replay crashing.
 */
public final class StateMachine {

    private static final Map<EntityKind, Set<EntityState>> STATES = new EnumMap<>(EntityKind.class);
    private static final Map<EntityState, Set<EntityState>> PERSON_EDGES = new EnumMap<>(EntityState.class);
    private static final Map<EntityState, Set<EntityState>> UNKNOWN_EDGES = new EnumMap<>(EntityState.class);

    static {
        Set<EntityState> calm = EnumSet.of(EntityState.NORMAL, EntityState.SUSPICIOUS, EntityState.AFRAID,
                EntityState.INVESTIGATING, EntityState.AVOIDING, EntityState.FOLLOWING, EntityState.ECHOING);
        STATES.put(EntityKind.PERSON, union(calm, EnumSet.of(EntityState.CORRUPTED, EntityState.AWARE,
                EntityState.MISSING)));
        STATES.put(EntityKind.UNKNOWN, EnumSet.of(EntityState.DORMANT, EntityState.WANDERING, EntityState.STALKING,
                EntityState.MIMICKING));
        STATES.put(EntityKind.PROCESS, EnumSet.of(EntityState.RUNNING, EntityState.BLOCKED, EntityState.TERMINATED));
        STATES.put(EntityKind.DEVICE, EnumSet.of(EntityState.ONLINE, EntityState.DEGRADED, EntityState.OFFLINE));
        STATES.put(EntityKind.CAMERA, EnumSet.of(EntityState.ONLINE, EntityState.DEGRADED, EntityState.OFFLINE));
        STATES.put(EntityKind.ROOM, EnumSet.of(EntityState.PRESENT));
        STATES.put(EntityKind.OBJECT, EnumSet.of(EntityState.PRESENT));
        STATES.put(EntityKind.SYSTEM, EnumSet.of(EntityState.PRESENT, EntityState.DEGRADED));

        // Ordinary emotional states flow freely between each other and can tip into corruption,
        // awareness or disappearance.
        for (EntityState s : calm) {
            PERSON_EDGES.put(s, union(calm, EnumSet.of(EntityState.CORRUPTED, EntityState.AWARE, EntityState.MISSING)));
        }
        // Corruption does not heal; it only deepens.
        PERSON_EDGES.put(EntityState.CORRUPTED, EnumSet.of(EntityState.ECHOING, EntityState.AWARE, EntityState.MISSING));
        PERSON_EDGES.put(EntityState.AWARE, EnumSet.of(EntityState.CORRUPTED, EntityState.MISSING));
        // The missing only come back changed.
        PERSON_EDGES.put(EntityState.MISSING, EnumSet.of(EntityState.AWARE));

        UNKNOWN_EDGES.put(EntityState.DORMANT, EnumSet.of(EntityState.WANDERING));
        UNKNOWN_EDGES.put(EntityState.WANDERING, EnumSet.of(EntityState.DORMANT, EntityState.STALKING,
                EntityState.MIMICKING));
        UNKNOWN_EDGES.put(EntityState.STALKING, EnumSet.of(EntityState.WANDERING, EntityState.MIMICKING,
                EntityState.DORMANT));
        UNKNOWN_EDGES.put(EntityState.MIMICKING, EnumSet.of(EntityState.WANDERING, EntityState.STALKING));
    }

    private StateMachine() {
    }

    public static Set<EntityState> statesFor(EntityKind kind) {
        return EnumSet.copyOf(STATES.get(kind));
    }

    public static boolean valid(EntityKind kind, EntityState state) {
        return STATES.get(kind).contains(state);
    }

    public static boolean canTransition(EntityKind kind, EntityState from, EntityState to) {
        if (from == to || !valid(kind, from) || !valid(kind, to)) {
            return false;
        }
        return switch (kind) {
            case PERSON -> PERSON_EDGES.getOrDefault(from, Set.of()).contains(to);
            case UNKNOWN -> UNKNOWN_EDGES.getOrDefault(from, Set.of()).contains(to);
            // Machines may move between any of their states.
            case PROCESS, DEVICE, CAMERA, SYSTEM -> true;
            case ROOM, OBJECT -> false;
        };
    }

    private static Set<EntityState> union(Set<EntityState> a, Set<EntityState> b) {
        EnumSet<EntityState> out = EnumSet.copyOf(a);
        out.addAll(b);
        return out;
    }
}
