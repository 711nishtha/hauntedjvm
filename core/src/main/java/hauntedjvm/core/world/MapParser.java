package hauntedjvm.core.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Parses the plain-text floor plan format documented at the top of {@code facility-07.map}. */
final class MapParser {

    private static final int[][] DIRECTIONS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    private MapParser() {
    }

    private record RoomDecl(char glyph, String code, RoomKind kind, String label) {
    }

    private record CameraDecl(String code, char glyph, CameraMount.Corner corner, boolean hidden) {
    }

    private record Grid(int width, int height, Tile[] tiles, char[] glyphs) {
        int index(int x, int y) {
            return y * width + x;
        }

        boolean inside(int x, int y) {
            return x >= 0 && y >= 0 && x < width && y < height;
        }
    }

    static FacilityMap parse(String name, String text) {
        Map<Character, RoomDecl> roomDecls = new LinkedHashMap<>();
        List<CameraDecl> cameraDecls = new ArrayList<>();
        List<String> rows = new ArrayList<>();
        boolean inMap = false;
        int lineNo = 0;
        for (String raw : text.split("\\R")) {
            lineNo++;
            if (inMap) {
                if (!raw.isBlank()) {
                    rows.add(raw);
                }
                continue;
            }
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\s+");
            switch (parts[0]) {
                case "room" -> {
                    require(parts.length >= 5, lineNo, "room <glyph> <code> <kind> <label>");
                    char glyph = single(parts[1], lineNo);
                    String label = String.join(" ", List.of(parts).subList(4, parts.length));
                    roomDecls.put(glyph, new RoomDecl(glyph, parts[2], RoomKind.valueOf(parts[3]), label));
                }
                case "camera" -> {
                    require(parts.length >= 4, lineNo, "camera <code> <glyph> <corner> [hidden]");
                    boolean hidden = parts.length > 4 && parts[4].equals("hidden");
                    cameraDecls.add(new CameraDecl(parts[1], single(parts[2], lineNo),
                            CameraMount.Corner.valueOf(parts[3]), hidden));
                }
                case "map" -> inMap = true;
                default -> throw new IllegalArgumentException("line " + lineNo + ": unknown directive " + parts[0]);
            }
        }
        require(!rows.isEmpty(), lineNo, "map section is empty");
        Grid grid = tokenize(rows, roomDecls);
        return build(name, grid, roomDecls, cameraDecls);
    }

    private static Grid tokenize(List<String> rows, Map<Character, RoomDecl> decls) {
        int height = rows.size();
        int width = rows.stream().mapToInt(String::length).max().orElseThrow();
        Tile[] tiles = new Tile[width * height];
        char[] glyphs = new char[width * height];
        for (int y = 0; y < height; y++) {
            String row = rows.get(y);
            for (int x = 0; x < width; x++) {
                char ch = x < row.length() ? row.charAt(x) : ' ';
                int i = y * width + x;
                glyphs[i] = ch;
                tiles[i] = switch (ch) {
                    case ' ' -> Tile.OUTSIDE;
                    case '#' -> Tile.WALL;
                    case '+' -> Tile.DOOR;
                    case '=' -> Tile.SEALED_DOOR;
                    case '*' -> Tile.TERMINAL;
                    default -> {
                        if (!decls.containsKey(ch)) {
                            throw new IllegalArgumentException("undeclared glyph '" + ch + "' at " + x + "," + y);
                        }
                        yield Tile.FLOOR;
                    }
                };
            }
        }
        Grid grid = new Grid(width, height, tiles, glyphs);
        // Terminals are furniture inside a room; they inherit the room of a neighbouring floor cell.
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (tiles[grid.index(x, y)] == Tile.TERMINAL) {
                    glyphs[grid.index(x, y)] = neighbourGlyph(grid, x, y, decls);
                }
            }
        }
        return grid;
    }

    private static FacilityMap build(String name, Grid grid, Map<Character, RoomDecl> decls,
                                     List<CameraDecl> cameraDecls) {
        Map<Character, List<Cell>> cellsByGlyph = new LinkedHashMap<>();
        Map<Character, List<Cell>> terminalsByGlyph = new LinkedHashMap<>();
        for (int y = 0; y < grid.height(); y++) {
            for (int x = 0; x < grid.width(); x++) {
                int i = grid.index(x, y);
                Tile t = grid.tiles()[i];
                if (t == Tile.FLOOR || t == Tile.TERMINAL) {
                    cellsByGlyph.computeIfAbsent(grid.glyphs()[i], k -> new ArrayList<>()).add(new Cell(x, y));
                    if (t == Tile.TERMINAL) {
                        terminalsByGlyph.computeIfAbsent(grid.glyphs()[i], k -> new ArrayList<>()).add(new Cell(x, y));
                    }
                }
            }
        }

        List<RoomLayout> rooms = new ArrayList<>();
        Map<Character, RoomLayout> roomsByGlyph = new LinkedHashMap<>();
        for (RoomDecl d : decls.values()) {
            List<Cell> cells = cellsByGlyph.getOrDefault(d.glyph(), List.of());
            if (cells.isEmpty()) {
                throw new IllegalArgumentException("room " + d.code() + " has no cells");
            }
            RoomLayout room = layout(d, cells, terminalsByGlyph.getOrDefault(d.glyph(), List.of()));
            rooms.add(room);
            roomsByGlyph.put(d.glyph(), room);
        }
        RoomLayout[] roomIndex = new RoomLayout[grid.width() * grid.height()];
        for (int i = 0; i < roomIndex.length; i++) {
            Tile t = grid.tiles()[i];
            if (t == Tile.FLOOR || t == Tile.TERMINAL) {
                roomIndex[i] = roomsByGlyph.get(grid.glyphs()[i]);
            }
        }

        List<DoorLayout> doors = new ArrayList<>();
        for (int y = 0; y < grid.height(); y++) {
            for (int x = 0; x < grid.width(); x++) {
                Tile t = grid.tiles()[grid.index(x, y)];
                if (t == Tile.DOOR || t == Tile.SEALED_DOOR) {
                    doors.add(door(doors.size(), new Cell(x, y), t == Tile.SEALED_DOOR, grid, roomIndex));
                }
            }
        }

        List<CameraMount> cameras = new ArrayList<>();
        for (CameraDecl c : cameraDecls) {
            RoomLayout room = roomsByGlyph.get(c.glyph());
            if (room == null) {
                throw new IllegalArgumentException("camera " + c.code() + " references unknown room glyph " + c.glyph());
            }
            cameras.add(new CameraMount(c.code(), room.code(), corner(room, c.corner()), c.corner(), c.hidden()));
        }
        return new FacilityMap(name, grid.width(), grid.height(), grid.tiles(), roomIndex, rooms, doors, cameras);
    }

    private static RoomLayout layout(RoomDecl d, List<Cell> cells, List<Cell> terminals) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (Cell c : cells) {
            minX = Math.min(minX, c.x());
            minY = Math.min(minY, c.y());
            maxX = Math.max(maxX, c.x());
            maxY = Math.max(maxY, c.y());
        }
        Cell centre = nearest(cells, (minX + maxX) / 2.0, (minY + maxY) / 2.0);
        Set<Cell> anchors = new LinkedHashSet<>(terminals);
        anchors.add(centre);
        if (d.kind() == RoomKind.CORRIDOR) {
            // Long thin spaces get anchors every few metres so patrols spread along them.
            boolean horizontal = maxX - minX >= maxY - minY;
            int span = horizontal ? maxX - minX : maxY - minY;
            for (int i = 4; i < span; i += 9) {
                double ax = horizontal ? minX + i : (minX + maxX) / 2.0;
                double ay = horizontal ? (minY + maxY) / 2.0 : minY + i;
                anchors.add(nearest(cells, ax, ay));
            }
        } else {
            double qx = (maxX - minX) / 4.0;
            double qy = (maxY - minY) / 4.0;
            anchors.add(nearest(cells, minX + qx, minY + qy));
            anchors.add(nearest(cells, maxX - qx, minY + qy));
            anchors.add(nearest(cells, minX + qx, maxY - qy));
            anchors.add(nearest(cells, maxX - qx, maxY - qy));
        }
        List<Cell> sortedCells = new ArrayList<>(cells);
        sortedCells.sort(Comparator.naturalOrder());
        return new RoomLayout(d.code(), d.glyph(), d.kind(), d.label(), sortedCells, new ArrayList<>(anchors),
                terminals, centre, minX, minY, maxX, maxY);
    }

    private static Cell nearest(List<Cell> cells, double x, double y) {
        Cell best = cells.getFirst();
        double bestD = Double.MAX_VALUE;
        for (Cell c : cells) {
            double dd = (c.x() - x) * (c.x() - x) + (c.y() - y) * (c.y() - y);
            if (dd < bestD) {
                bestD = dd;
                best = c;
            }
        }
        return best;
    }

    private static DoorLayout door(int index, Cell cell, boolean sealed, Grid grid, RoomLayout[] roomIndex) {
        RoomLayout n = at(grid, roomIndex, cell.offset(0, -1));
        RoomLayout s = at(grid, roomIndex, cell.offset(0, 1));
        RoomLayout w = at(grid, roomIndex, cell.offset(-1, 0));
        RoomLayout e = at(grid, roomIndex, cell.offset(1, 0));
        RoomLayout a;
        RoomLayout b;
        if (n != null && s != null) {
            a = n;
            b = s;
        } else if (w != null && e != null) {
            a = w;
            b = e;
        } else {
            throw new IllegalArgumentException("door at " + cell + " does not join two rooms");
        }
        String code = String.format("DOOR-%02d", index + 1);
        return new DoorLayout(index, code, cell, a.code(), b.code(), sealed);
    }

    private static RoomLayout at(Grid grid, RoomLayout[] index, Cell c) {
        return grid.inside(c.x(), c.y()) ? index[grid.index(c.x(), c.y())] : null;
    }

    private static Cell corner(RoomLayout r, CameraMount.Corner corner) {
        int x = switch (corner) {
            case NW, SW -> r.minX();
            case NE, SE -> r.maxX();
        };
        int y = switch (corner) {
            case NW, NE -> r.minY();
            case SW, SE -> r.maxY();
        };
        return nearest(r.cells(), x, y);
    }

    private static char neighbourGlyph(Grid grid, int x, int y, Map<Character, RoomDecl> decls) {
        for (int[] d : DIRECTIONS) {
            int nx = x + d[0];
            int ny = y + d[1];
            if (grid.inside(nx, ny)) {
                int i = grid.index(nx, ny);
                if (grid.tiles()[i] == Tile.FLOOR && decls.containsKey(grid.glyphs()[i])) {
                    return grid.glyphs()[i];
                }
            }
        }
        throw new IllegalArgumentException("terminal at " + x + "," + y + " is not inside a room");
    }

    private static void require(boolean condition, int line, String message) {
        if (!condition) {
            throw new IllegalArgumentException("line " + line + ": " + message);
        }
    }

    private static char single(String s, int line) {
        require(s.length() == 1, line, "expected a single glyph, got '" + s + "'");
        return s.charAt(0);
    }
}
