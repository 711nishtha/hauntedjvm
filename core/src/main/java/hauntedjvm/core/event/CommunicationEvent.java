package hauntedjvm.core.event;

import hauntedjvm.core.entity.EntityId;
import java.util.List;

/** Speech, radio, intercom and terminal traffic. */
public sealed interface CommunicationEvent extends SimEvent {

    enum Channel {
        VOICE,
        RADIO,
        INTERCOM,
        TERMINAL,
        INCIDENT_LOG
    }

    /**
     * What a message asserts, in machine-checkable form. The detector compares a claim against
     * the speaker's own memory; if they differ, the story changed in the retelling.
     *
     * @param aboutMemory id of the speaker's memory the claim is based on, or -1
     */
    record Claim(String roomCode, EntityId subject, double valence, long aboutMemory) {
    }

    /**
     * @param sender    {@code null} when nothing sent it
     * @param recipient {@code null} for broadcasts
     * @param claim     structured content, if the message makes a factual claim
     */
    record MessageSent(EntityId sender, EntityId recipient, Channel channel, String text, Claim claim)
            implements CommunicationEvent {
        @Override
        public List<EntityId> subjects() {
            return claim == null ? SimEvent.of(sender, recipient) : SimEvent.of(sender, recipient, claim.subject());
        }
    }

    record MessageReceived(long messageSeq, EntityId recipient) implements CommunicationEvent {
        @Override
        public List<EntityId> subjects() {
            return SimEvent.of(recipient);
        }
    }
}
