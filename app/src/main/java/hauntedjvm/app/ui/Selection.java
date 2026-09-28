package hauntedjvm.app.ui;

import hauntedjvm.core.entity.EntityId;

/** What the inspector is looking at. {@link #ref()} is the form recorded in notes and inspection events. */
public sealed interface Selection {

    String ref();

    record None() implements Selection {
        @Override
        public String ref() {
            return "session";
        }
    }

    record OfEntity(EntityId id) implements Selection {
        @Override
        public String ref() {
            return "entity:" + id;
        }
    }

    record OfEvent(long seq) implements Selection {
        @Override
        public String ref() {
            return "event:" + seq;
        }
    }

    record OfAnomaly(String id) implements Selection {
        @Override
        public String ref() {
            return "anomaly:" + id;
        }
    }

    /** A real JVM thread; never recorded into the simulation. */
    record OfThread(long threadId, String name) implements Selection {
        @Override
        public String ref() {
            return "jvm-thread:" + threadId;
        }
    }
}
