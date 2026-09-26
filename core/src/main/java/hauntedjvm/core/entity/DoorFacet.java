package hauntedjvm.core.entity;

/**
 * @param closeAt tick at which an open door swings shut on its own
 */
public record DoorFacet(String code, int index, boolean open, boolean locked, boolean sealed, long closeAt)
        implements Facet {

    public boolean blocked() {
        return locked || sealed;
    }

    public DoorFacet opened(long until) {
        return new DoorFacet(code, index, true, locked, sealed, until);
    }

    public DoorFacet closed() {
        return new DoorFacet(code, index, false, locked, sealed, -1);
    }

    public DoorFacet withLock(boolean lock) {
        return new DoorFacet(code, index, !lock && open, lock, sealed, lock ? -1 : closeAt);
    }

    public DoorFacet unsealed() {
        return new DoorFacet(code, index, open, locked, false, closeAt);
    }
}
