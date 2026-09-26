package hauntedjvm.core.entity;

/**
 * @param normal     the light the room returns to when a temporary mode expires
 * @param lightUntil tick at which a temporary light mode reverts to {@code normal}; -1 if permanent
 * @param listed     whether the room appears in the facility registry
 */
public record RoomFacet(String code, String label, LightMode light, LightMode normal, long lightUntil, boolean listed)
        implements Facet {

    public RoomFacet withLight(LightMode mode, long until) {
        return new RoomFacet(code, label, mode, normal, until, listed);
    }

    public RoomFacet revealed() {
        return new RoomFacet(code, label, light, normal, lightUntil, true);
    }
}
