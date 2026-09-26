package hauntedjvm.core.incident;

/**
 * An instruction the operator broadcast over the intercom.
 *
 * @param roomCode target room for {@link Type#GATHER}; ignored otherwise
 */
public record Directive(Type type, String roomCode, long issuedTick, long until) {

    public enum Type {
        /** Everyone to one room. Company calms people; crowds are also easy targets. */
        GATHER,
        /** Back to your stations. */
        RETURN_TO_ROUTINE
    }

    public boolean activeAt(long tick) {
        return tick >= issuedTick && tick < until;
    }
}
