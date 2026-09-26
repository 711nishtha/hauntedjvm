package hauntedjvm.core.entity;

/** A workstation. Processes are hosted on terminals in the server hall. */
public record TerminalFacet(String roomCode, String hostname) implements Facet {
}
