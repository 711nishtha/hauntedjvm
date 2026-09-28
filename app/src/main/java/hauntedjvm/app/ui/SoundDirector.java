package hauntedjvm.app.ui;

import hauntedjvm.audio.AudioEngine;
import hauntedjvm.audio.AudioMood;
import hauntedjvm.audio.Cue;
import hauntedjvm.core.behavior.Perception;
import hauntedjvm.core.entity.CameraFacet;
import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityKind;
import hauntedjvm.core.event.AnomalyEvent;
import hauntedjvm.core.event.CommunicationEvent;
import hauntedjvm.core.event.EnvironmentEvent;
import hauntedjvm.core.event.EventRecord;
import hauntedjvm.core.event.OperatorEvent;
import hauntedjvm.core.state.WorldView;
import hauntedjvm.core.telemetry.SimulationMetrics;
import java.util.EnumMap;
import java.util.Map;

/**
 * Translates what is on screen into sound: a continuous mood from the watched room and the
 * incident level, and short cues for notable events. Cues are rate-limited per kind so a burst
 * of events never becomes a wall of noise.
 */
final class SoundDirector {

    private static final long MIN_GAP_NANOS = 350_000_000L;

    private final AudioEngine audio;
    private final Map<Cue, Long> lastPlayed = new EnumMap<>(Cue.class);
    private long moodUpdated;

    SoundDirector(AudioEngine audio) {
        this.audio = audio;
    }

    void mood(Display d, Feed feed, long now) {
        if (now - moodUpdated < 100_000_000L) {
            return;
        }
        moodUpdated = now;
        WorldView w = d.world();
        SimulationMetrics m = SimulationMetrics.of(w);
        double tension = Math.min(1, m.instability() / 9.0 * 0.7 + m.meanFear() * 0.5);
        double signal = 0;
        double darkness = 0;
        double presence = 0;
        if (feed instanceof Feed.Camera c && w.camera(c.code()) != null) {
            CameraFacet cf = (CameraFacet) w.camera(c.code()).facet();
            signal = switch (cf.status()) {
                case ONLINE -> 0.03;
                case INTERFERENCE -> 0.8;
                case LOOPING -> 0.3;
                case OFFLINE -> 1.0;
            };
            darkness = 1 - Perception.light(w, cf.roomCode(), w.tick());
            for (Entity e : w.occupants(cf.roomCode())) {
                if (e.kind() == EntityKind.UNKNOWN) {
                    presence = 1;
                }
            }
        }
        audio.setMood(new AudioMood(tension, w.incident().level() / 5.0, signal, darkness, presence));
    }

    void onEvent(EventRecord r, WorldView w, long now) {
        Cue cue = switch (r.event()) {
            case AnomalyEvent.AnomalyDetected a when a.anomaly().severity() >= 2 -> Cue.BLIP;
            case AnomalyEvent.AnomalyEscalated a when a.to() > a.from() -> Cue.ESCALATE;
            case CommunicationEvent.MessageSent m when m.sender() == null -> Cue.RADIO;
            case EnvironmentEvent.DoorClosed dc when watched(w, dc.door()) -> Cue.DOOR;
            case EnvironmentEvent.CameraStatusChanged c when c.status() != hauntedjvm.core.entity.CameraStatus.ONLINE
                    -> Cue.STATIC_BURST;
            case OperatorEvent.TimelineRewound t -> Cue.REWIND;
            default -> null;
        };
        if (cue != null) {
            play(cue, now);
        }
    }

    void play(Cue cue, long now) {
        Long previous = lastPlayed.get(cue);
        if (previous == null || now - previous > MIN_GAP_NANOS) {
            lastPlayed.put(cue, now);
            audio.trigger(cue);
        }
    }

    private static boolean watched(WorldView w, hauntedjvm.core.entity.EntityId door) {
        Entity e = w.entity(door);
        String room = w.observedRoom();
        return e != null && room != null && w.map().doorAt(e.position()) != null
                && w.map().doorAt(e.position()).connects(room);
    }
}
