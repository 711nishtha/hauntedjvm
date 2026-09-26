package hauntedjvm.core.memory;

import hauntedjvm.core.behavior.Reactions;
import hauntedjvm.core.engine.SimulationSystem;
import hauntedjvm.core.engine.TickContext;
import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityKind;
import hauntedjvm.core.entity.EntityState;
import hauntedjvm.core.entity.MemoryKind;
import hauntedjvm.core.entity.MemoryTrace;
import hauntedjvm.core.entity.Mind;
import hauntedjvm.core.entity.RoomFacet;
import hauntedjvm.core.event.CommunicationEvent.Channel;
import hauntedjvm.core.event.CommunicationEvent.Claim;
import hauntedjvm.core.event.CommunicationEvent.MessageReceived;
import hauntedjvm.core.event.CommunicationEvent.MessageSent;
import hauntedjvm.core.event.EntityEvent.RelationshipChanged;
import hauntedjvm.core.event.EventRecord;
import hauntedjvm.core.random.Rng;
import hauntedjvm.core.random.Streams;
import hauntedjvm.core.state.WorldView;
import java.util.ArrayList;
import java.util.List;

/**
 * Conversation, and therefore rumour.
 *
 * <p>When two people share a room they sometimes talk. If one of them is carrying a frightening
 * memory, that is what they talk about, and the listener forms a second-hand memory weighted by
 * how much they trust the speaker. Paranoid or corrupted speakers misremember where it happened.
 * A retold memory that no longer matches its origin is exactly the kind of inconsistency the
 * detector reports, so rumours generate memory anomalies without any anomaly code being involved.
 */
public final class SocialSystem implements SimulationSystem {

    private static final int TALK_PERIOD = 30;
    private static final int RADIO_PERIOD = 40;

    private static final List<String> SMALL_TALK = List.of(
            "Coffee's gone cold again.",
            "Did you log the two o'clock check?",
            "Generator's louder tonight.",
            "Long shift.",
            "Tape seven still isn't back on the rack.",
            "Heard the intercom click. Nobody on it.",
            "Who's on cameras tonight?",
            "The lights in the south corridor keep humming.",
            "I'll walk you back after this.");

    @Override
    public String name() {
        return "social";
    }

    @Override
    public void tick(TickContext ctx) {
        WorldView w = ctx.world();
        long t = ctx.tick();
        for (Entity speaker : w.ofKind(EntityKind.PERSON)) {
            if (!speaker.present() || speaker.state() == EntityState.CORRUPTED) {
                continue;
            }
            int phase = speaker.id().value() * 7;
            if ((t + phase) % TALK_PERIOD == 0) {
                talk(ctx, w.entity(speaker.id()));
            }
            if (speaker.state() == EntityState.AFRAID && (t + phase) % RADIO_PERIOD == 0) {
                radio(ctx, w.entity(speaker.id()));
            }
        }
    }

    private void talk(TickContext ctx, Entity speaker) {
        WorldView w = ctx.world();
        String room = w.roomOf(speaker);
        if (room == null) {
            return;
        }
        List<Entity> listeners = new ArrayList<>();
        for (Entity e : w.occupants(room)) {
            if (e.kind() == EntityKind.PERSON && !e.id().equals(speaker.id()) && e.state() != EntityState.CORRUPTED) {
                listeners.add(e);
            }
        }
        if (listeners.isEmpty()) {
            return;
        }
        Mind mind = speaker.mind();
        Rng rng = ctx.rng(Streams.SOCIAL, speaker.id().value());
        if (!rng.chance(0.5 * (1 - mind.traits().paranoia() * 0.6))) {
            return;
        }
        Entity listener = rng.pick(listeners);
        MemoryTrace worry = mind.mostSalientFear(ctx.tick(), 0.25).orElse(null);
        if (worry != null && alreadyHeard(ctx, listener, worry)) {
            worry = null;
        }
        if (worry != null) {
            retell(ctx, speaker, listener, worry);
        } else if (rng.chance(0.25)) {
            EventRecord msg = ctx.emit(new MessageSent(speaker.id(), listener.id(), Channel.VOICE,
                    rng.pick(SMALL_TALK), null));
            ctx.emit(new MessageReceived(msg.seq(), listener.id()));
            ctx.emit(new RelationshipChanged(speaker.id(), listener.id(), 0.01, 0.04));
            ctx.emit(new RelationshipChanged(listener.id(), speaker.id(), 0.01, 0.04));
            Reactions.feel(ctx, w.entity(listener.id()), -0.03, 0, 0, "company");
        }
    }

    private void retell(TickContext ctx, Entity speaker, Entity listener, MemoryTrace worry) {
        WorldView w = ctx.world();
        Mind mind = speaker.mind();
        Rng distortion = ctx.rng(Streams.DISTORTION, speaker.id().value());
        String room = worry.roomCode();
        double distortChance = mind.traits().paranoia() * 0.2 + speaker.corruption() * 0.3;
        if (distortion.chance(distortChance)) {
            List<String> rooms = listedRooms(w);
            rooms.remove(room);
            if (!rooms.isEmpty()) {
                room = distortion.pick(rooms);
            }
        }
        Entity subject = w.entity(worry.subject());
        String text = describe(subject, room, worry, distortion);
        Claim claim = new Claim(room, worry.subject(), worry.valence(), worry.id());
        EventRecord msg = ctx.emit(new MessageSent(speaker.id(), listener.id(), Channel.VOICE, text, claim));
        ctx.emit(new MessageReceived(msg.seq(), listener.id()));

        double trust = listener.mind().relationship(speaker.id()).map(r -> r.trust()).orElse(0.4);
        Reactions.remember(ctx, listener.id(), new MemoryTrace(0, ctx.tick(), MemoryKind.HEARD, worry.subject(), room,
                null, 0.3 + 0.5 * trust, worry.valence() * 0.8, msg.seq(), speaker.name() + ": " + text));
        Reactions.feel(ctx, w.entity(listener.id()), 0.1 * Math.abs(worry.valence())
                * (0.5 + listener.mind().traits().fear()) * (0.5 + trust), 0.01, 0, "told by " + speaker.name());
        ctx.emit(new RelationshipChanged(listener.id(), speaker.id(), 0.0, 0.03));
    }

    /** People do not tell the same story to the same listener twice. */
    private static boolean alreadyHeard(TickContext ctx, Entity listener, MemoryTrace worry) {
        for (MemoryTrace m : listener.mind().memories()) {
            if (m.kind() == MemoryKind.HEARD && ctx.log().contains(m.sourceSeq())
                    && ctx.log().get(m.sourceSeq()).event() instanceof MessageSent sent
                    && sent.claim() != null && sent.claim().aboutMemory() == worry.id()) {
                return true;
            }
        }
        return false;
    }

    private void radio(TickContext ctx, Entity speaker) {
        WorldView w = ctx.world();
        String room = w.roomOf(speaker);
        Rng rng = ctx.rng(Streams.SOCIAL, speaker.id().value() + 100_000L);
        if (room == null || !rng.chance(0.3)) {
            return;
        }
        String text = speaker.name() + " to anyone. I'm in " + room + ". Is anyone else hearing this?";
        EventRecord msg = ctx.emit(new MessageSent(speaker.id(), null, Channel.RADIO, text,
                new Claim(room, speaker.id(), -0.6, -1)));
        // Radio only reaches people who trust the caller enough to keep their handset on.
        int delivered = 0;
        for (Entity other : w.ofKind(EntityKind.PERSON)) {
            if (delivered >= 5 || !other.present() || other.id().equals(speaker.id())) {
                continue;
            }
            double trust = other.mind().relationship(speaker.id()).map(r -> r.trust()).orElse(0.0);
            if (trust >= 0.55) {
                ctx.emit(new MessageReceived(msg.seq(), other.id()));
                Reactions.remember(ctx, other.id(), new MemoryTrace(0, ctx.tick(), MemoryKind.HEARD, speaker.id(), room,
                        null, 0.35, -0.5, msg.seq(), "radio: " + speaker.name() + " scared in " + room));
                delivered++;
            }
        }
    }

    private static String describe(Entity subject, String room, MemoryTrace worry, Rng rng) {
        if (subject != null && subject.kind() == EntityKind.PERSON) {
            return rng.pick(List.of(
                    subject.name() + " was in " + room + ". It wasn't really them.",
                    "Something's wrong with " + subject.name() + ". Saw it in " + room + ".",
                    "Don't let " + subject.name() + " walk you to " + room + "."));
        }
        if (worry.kind() == MemoryKind.PREVIOUS_TIMELINE) {
            return "I've been in " + room + " already tonight. I remember how it ends.";
        }
        return rng.pick(List.of(
                "There's something in " + room + ".",
                "Don't go into " + room + " alone.",
                "I saw something in " + room + ". I'm not going back.",
                "The lights in " + room + " went strange and something moved."));
    }

    private static List<String> listedRooms(WorldView w) {
        List<String> rooms = new ArrayList<>();
        for (Entity e : w.ofKind(EntityKind.ROOM)) {
            if (e.facet() instanceof RoomFacet rf && rf.listed()) {
                rooms.add(rf.code());
            }
        }
        return rooms;
    }
}
