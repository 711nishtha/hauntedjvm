package hauntedjvm.app.ui;

import hauntedjvm.core.entity.EntityId;
import hauntedjvm.core.event.EntityEvent;
import hauntedjvm.core.event.EventLog;
import hauntedjvm.core.event.EventRecord;
import hauntedjvm.core.timeline.Timeline;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Recent notable events per entity, maintained incrementally as the log grows.
 *
 * <p>The inspector refreshes several times a second while the selected person walks around;
 * rescanning tens of thousands of records each time caused visible hitches. Here each record is
 * looked at once. A request for a point before the indexed range (reviewing the past) falls back
 * to a bounded backward scan.
 */
final class ActivityIndex {

    static final int KEEP = 12;
    private static final int FALLBACK_SCAN = 30_000;

    /** Notable events about one entity, newest last, plus the latest state-change reason. */
    record Activity(List<EventRecord> recent, String lastReason) {
    }

    private static final class Entry {
        final Deque<EventRecord> recent = new ArrayDeque<>();
        String lastReason;
    }

    private Timeline timeline;
    private long indexedTo;
    private final Map<EntityId, Entry> entries = new HashMap<>();

    Activity lookup(Timeline current, long limit, EntityId id) {
        if (current != timeline) {
            timeline = current;
            indexedTo = 0;
            entries.clear();
        }
        if (limit < indexedTo) {
            return scanBackward(current.log(), limit, id);
        }
        EventLog log = current.log();
        // Catching up from nothing only needs the tail; older history is served by the fallback.
        long from = Math.max(indexedTo, limit - FALLBACK_SCAN);
        for (long s = from; s < limit; s++) {
            EventRecord r = log.get(s);
            if (r.event().internal() || EventText.routine(r.event())) {
                continue;
            }
            for (EntityId subject : r.event().subjects()) {
                Entry e = entries.computeIfAbsent(subject, k -> new Entry());
                e.recent.addLast(r);
                if (e.recent.size() > KEEP) {
                    e.recent.removeFirst();
                }
                if (r.event() instanceof EntityEvent.EntityStateChanged c && c.id().equals(subject)
                        && c.reason() != null) {
                    e.lastReason = c.reason();
                }
            }
        }
        indexedTo = limit;
        Entry e = entries.get(id);
        return e == null ? new Activity(List.of(), null) : new Activity(List.copyOf(e.recent), e.lastReason);
    }

    private static Activity scanBackward(EventLog log, long limit, EntityId id) {
        List<EventRecord> found = new ArrayList<>();
        String reason = null;
        for (long s = limit - 1; s >= Math.max(0, limit - FALLBACK_SCAN); s--) {
            EventRecord r = log.get(s);
            if (r.event().internal() || !r.event().subjects().contains(id)) {
                continue;
            }
            if (reason == null && r.event() instanceof EntityEvent.EntityStateChanged c && c.id().equals(id)) {
                reason = c.reason();
            }
            if (found.size() < KEEP && !EventText.routine(r.event())) {
                found.add(r);
            }
            if (found.size() >= KEEP && reason != null) {
                break;
            }
        }
        return new Activity(found.reversed(), reason);
    }
}
