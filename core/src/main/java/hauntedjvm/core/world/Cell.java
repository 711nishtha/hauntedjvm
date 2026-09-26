package hauntedjvm.core.world;

/** A 1m x 1m grid cell. Immutable and cheap; used as a map key throughout the simulation. */
public record Cell(int x, int y) implements Comparable<Cell> {

    public int manhattan(Cell other) {
        return Math.abs(x - other.x) + Math.abs(y - other.y);
    }

    public double distance(Cell other) {
        return Math.hypot(x - other.x, y - other.y);
    }

    public Cell offset(int dx, int dy) {
        return new Cell(x + dx, y + dy);
    }

    /** Row-major ordering, so sorted cell collections iterate like the map file reads. */
    @Override
    public int compareTo(Cell o) {
        return y != o.y ? Integer.compare(y, o.y) : Integer.compare(x, o.x);
    }

    @Override
    public String toString() {
        return "(" + x + "," + y + ")";
    }
}
