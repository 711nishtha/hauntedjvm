package hauntedjvm.core.entity;

/**
 * Kind-specific state attached to an {@link Entity}.
 *
 * <p>A sealed hierarchy instead of a bag of string attributes: every consumer pattern-matches
 * over the permitted facets, so adding a new one is a compile error everywhere it matters.
 */
public sealed interface Facet
        permits Mind, RoomFacet, DoorFacet, CameraFacet, ProcessFacet, ItemFacet, TerminalFacet, SystemFacet {
}
