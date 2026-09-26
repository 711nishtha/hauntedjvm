package hauntedjvm.core.world;

import java.util.List;

/**
 * Static geometry of one room.
 *
 * @param anchors cells entities walk to: terminals, the centre and quadrant points. Keeping
 *                destinations to a small fixed set lets the navigator share flow fields.
 */
public record RoomLayout(
        String code,
        char glyph,
        RoomKind kind,
        String label,
        List<Cell> cells,
        List<Cell> anchors,
        List<Cell> terminals,
        Cell centre,
        int minX,
        int minY,
        int maxX,
        int maxY) {

    public RoomLayout {
        cells = List.copyOf(cells);
        anchors = List.copyOf(anchors);
        terminals = List.copyOf(terminals);
    }

    public boolean listed() {
        return kind != RoomKind.HIDDEN;
    }

    public int width() {
        return maxX - minX + 1;
    }

    public int height() {
        return maxY - minY + 1;
    }

    public boolean contains(Cell cell) {
        return cell.x() >= minX && cell.x() <= maxX && cell.y() >= minY && cell.y() <= maxY;
    }
}
