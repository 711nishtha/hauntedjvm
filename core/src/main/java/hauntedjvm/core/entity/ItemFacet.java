package hauntedjvm.core.entity;

import hauntedjvm.core.world.Cell;

/** A physical object. {@code home} is where it was placed at genesis. */
public record ItemFacet(String description, Cell home) implements Facet {
}
