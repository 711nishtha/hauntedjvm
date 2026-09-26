package hauntedjvm.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import hauntedjvm.core.config.SimulationConfig;
import hauntedjvm.core.event.EventLog;
import hauntedjvm.core.event.EventRecord;
import hauntedjvm.core.state.WorldSnapshot;
import hauntedjvm.core.timeline.LogDigest;
import hauntedjvm.core.timeline.ReplayVerifier;
import hauntedjvm.core.timeline.Timeline;
import hauntedjvm.core.world.FacilityMap;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads and writes {@code .hjvm} session files.
 *
 * <p>A session file is a zip archive:
 * <pre>
 *   manifest.json            format version, seed, config, head tick
 *   events.ndjson            the event log, one record per line
 *   checkpoints/NNNNNNNN.json a sparse selection of world snapshots
 *   notes.json               the operator's notes
 *   discovered.json          anomalies the operator has examined
 * </pre>
 *
 * <p>The event log is the source of truth. On load the timeline is rebuilt purely by replaying
 * it, and the stored checkpoints are then used only as witnesses: if replay does not reproduce
 * them exactly, the file is rejected rather than silently showing a different history.
 */
public final class SessionArchive {

    public static final int FORMAT = 1;
    public static final String EXTENSION = ".hjvm";
    static final String MANIFEST = "manifest.json";
    static final String EVENTS = "events.ndjson";
    static final String NOTES = "notes.json";
    static final String DISCOVERED = "discovered.json";
    static final String CHECKPOINTS = "checkpoints/";
    /** Only every n-th tick's checkpoint is stored; the rest are rebuilt on load. */
    static final long STORED_CHECKPOINT_TICKS = 1_000;

    private static final Logger LOG = LoggerFactory.getLogger(SessionArchive.class);

    /** Archive header. */
    public record Manifest(int format, String application, String sessionId, String title, Instant createdAt,
                           Instant savedAt, SimulationConfig config, long headTick, long events, String digest) {
    }

    private final ObjectMapper mapper = JsonCodec.mapper();
    private final FacilityMap map;

    public SessionArchive(FacilityMap map) {
        this.map = map;
    }

    /** Writes atomically: a crash mid-save leaves the previous file intact. */
    public void save(Investigation inv, Path file) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, ".saving-", EXTENSION);
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(tmp))) {
                Manifest manifest = new Manifest(FORMAT, "HAUNTEDJVM", inv.sessionId(), inv.title(), inv.createdAt(),
                        Instant.now(), inv.config(), inv.headTick(), inv.eventCount(),
                        LogDigest.of(inv.timeline().log(), inv.eventCount()));
                writeJson(zip, MANIFEST, manifest);
                writeEvents(zip, inv.timeline().log(), inv.eventCount());
                for (WorldSnapshot s : inv.timeline().snapshots().all()) {
                    if (s.tick() % STORED_CHECKPOINT_TICKS == 0 && s.tick() <= inv.headTick()
                            && s.lastSeq() < inv.eventCount()) {
                        writeJson(zip, CHECKPOINTS + String.format("%012d.json", s.tick()), s);
                    }
                }
                writeJson(zip, NOTES, inv.notes());
                writeJson(zip, DISCOVERED, inv.discovered());
            }
            move(tmp, file);
            LOG.atInfo().addKeyValue("file", file).addKeyValue("events", inv.eventCount())
                    .addKeyValue("headTick", inv.headTick()).log("session saved");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public Investigation load(Path file) throws IOException {
        Manifest manifest = null;
        EventLog log = new EventLog();
        List<WorldSnapshot> checkpoints = new ArrayList<>();
        List<InvestigationNote> notes = List.of();
        Set<String> discovered = Set.of();
        boolean sawEvents = false;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(file))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.equals(MANIFEST)) {
                    manifest = mapper.readValue(zip.readAllBytes(), Manifest.class);
                } else if (name.equals(EVENTS)) {
                    readEvents(zip, log);
                    sawEvents = true;
                } else if (name.startsWith(CHECKPOINTS)) {
                    checkpoints.add(mapper.readValue(zip.readAllBytes(), WorldSnapshot.class));
                } else if (name.equals(NOTES)) {
                    notes = mapper.readValue(zip.readAllBytes(), new TypeReference<List<InvestigationNote>>() { });
                } else if (name.equals(DISCOVERED)) {
                    discovered = mapper.readValue(zip.readAllBytes(), new TypeReference<Set<String>>() { });
                }
            }
        } catch (JsonProcessingException e) {
            throw new SessionFormatException(file + " contains malformed data: " + e.getOriginalMessage(), e);
        }
        if (manifest == null || !sawEvents) {
            throw new SessionFormatException(file + " is not a HAUNTEDJVM session (missing manifest or events)");
        }
        if (manifest.format() != FORMAT) {
            throw new SessionFormatException(file + " uses session format " + manifest.format()
                    + "; this build reads format " + FORMAT);
        }
        if (log.size() != manifest.events()) {
            throw new SessionFormatException("expected " + manifest.events() + " events but found " + log.size());
        }
        if (!LogDigest.of(log).equals(manifest.digest())) {
            throw new SessionFormatException("event log digest mismatch: " + file + " is damaged or was edited");
        }
        Timeline timeline;
        try {
            timeline = Timeline.replay(map, log, manifest.headTick(), manifest.config().snapshotInterval(), s -> { });
        } catch (RuntimeException e) {
            throw new SessionFormatException("the event log does not replay: " + e.getMessage(), e);
        }
        List<ReplayVerifier.Mismatch> mismatches = ReplayVerifier.verify(map, log, checkpoints);
        if (!mismatches.isEmpty()) {
            throw new SessionFormatException("replay disagrees with the recorded checkpoints: " + mismatches);
        }
        LOG.atInfo().addKeyValue("file", file).addKeyValue("events", log.size())
                .addKeyValue("checkpointsVerified", checkpoints.size()).log("session loaded");
        return new Investigation(manifest.sessionId(), manifest.title(), manifest.createdAt(), manifest.config(),
                timeline, manifest.headTick(), log.size(), notes, discovered);
    }

    private void writeJson(ZipOutputStream zip, String name, Object value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(mapper.writeValueAsBytes(value));
        zip.closeEntry();
    }

    private void writeEvents(ZipOutputStream zip, EventLog log, long count) throws IOException {
        zip.putNextEntry(new ZipEntry(EVENTS));
        BufferedWriter out = new BufferedWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8));
        for (long seq = 0; seq < count; seq++) {
            out.write(mapper.writeValueAsString(log.get(seq)));
            out.write('\n');
        }
        // Flush without closing: closing the writer would close the whole zip stream.
        out.flush();
        zip.closeEntry();
    }

    private void readEvents(ZipInputStream zip, EventLog log) throws IOException {
        // Deliberately not closed: that would close the enclosing zip stream.
        BufferedReader in = new BufferedReader(new InputStreamReader(zip, StandardCharsets.UTF_8));
        String line;
        int lineNo = 0;
        while ((line = in.readLine()) != null) {
            lineNo++;
            if (line.isBlank()) {
                continue;
            }
            try {
                log.append(mapper.readValue(line, EventRecord.class));
            } catch (IllegalArgumentException e) {
                throw new SessionFormatException("event on line " + lineNo + " is out of order: " + e.getMessage(), e);
            }
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
