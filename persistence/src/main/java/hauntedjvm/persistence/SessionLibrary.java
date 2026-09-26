package hauntedjvm.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Where sessions live by default: {@code ~/.hauntedjvm/sessions}. The application writes
 * nowhere else unless the operator picks a different file in a save dialog.
 */
public final class SessionLibrary {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path directory;

    public SessionLibrary(Path directory) {
        this.directory = directory;
    }

    public static SessionLibrary inUserHome() {
        return new SessionLibrary(Path.of(System.getProperty("user.home"), ".hauntedjvm", "sessions"));
    }

    public Path directory() {
        return directory;
    }

    /** A fresh, human-sortable file name for a seed. */
    public Path newFile(long seed) {
        return directory.resolve(String.format("facility07-%016X-%s%s", seed, LocalDateTime.now().format(STAMP),
                SessionArchive.EXTENSION));
    }

    /** Saved sessions, newest first. Empty if nothing has been saved yet. */
    public List<Path> list() throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(p -> p.getFileName().toString().endsWith(SessionArchive.EXTENSION))
                    .sorted(Comparator.comparing((Path p) -> p.toFile().lastModified()).reversed())
                    .toList();
        }
    }
}
