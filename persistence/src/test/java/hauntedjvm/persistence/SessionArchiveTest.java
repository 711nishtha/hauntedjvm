package hauntedjvm.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import hauntedjvm.core.Simulations;
import hauntedjvm.core.config.SimulationConfig;
import hauntedjvm.core.engine.OperatorCommand;
import hauntedjvm.core.engine.SimulationEngine;
import hauntedjvm.core.timeline.LogDigest;
import hauntedjvm.core.world.FacilityMap;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionArchiveTest {

    private final SessionArchive archive = new SessionArchive(FacilityMap.facility07());

    private static Investigation capture(SimulationEngine e, List<InvestigationNote> notes, Set<String> discovered) {
        return new Investigation(UUID.randomUUID().toString(), null, Instant.parse("2026-09-26T03:00:00Z"), e.config(),
                e.timeline(), e.tick(), e.timeline().log().size(), notes, discovered);
    }

    @Test
    void saveThenLoadRestoresTheInvestigation(@TempDir Path dir) throws IOException {
        SimulationEngine engine = Simulations.create(SimulationConfig.defaults(31337));
        engine.run(1_500);
        engine.execute(new OperatorCommand.SwitchCamera("CAM-04"));
        engine.run(1_700);
        InvestigationNote note = InvestigationNote.write("entity:#003", 1_600, "MARA knew about the archive before it happened.");
        Investigation saved = capture(engine, List.of(note), Set.of("MEMORY:x@40"));

        Path file = dir.resolve("night.hjvm");
        archive.save(saved, file);
        Investigation loaded = archive.load(file);

        assertThat(loaded.config()).isEqualTo(saved.config());
        assertThat(loaded.headTick()).isEqualTo(3_200);
        assertThat(LogDigest.of(loaded.timeline().log())).isEqualTo(LogDigest.of(engine.timeline().log()));
        assertThat(loaded.timeline().reconstruct(engine.map(), 3_200).snapshot()).isEqualTo(engine.snapshot());
        assertThat(loaded.notes()).containsExactly(note);
        assertThat(loaded.discovered()).containsExactly("MEMORY:x@40");
        assertThat(loaded.title()).contains(saved.config().seedHex());
    }

    /** The strongest claim the format makes: a loaded session continues exactly as if it had never stopped. */
    @Test
    void aLoadedSessionContinuesExactlyAsTheOriginalWould(@TempDir Path dir) throws IOException {
        SimulationConfig config = SimulationConfig.defaults(606);
        SimulationEngine uninterrupted = Simulations.create(config);
        uninterrupted.run(3_000);

        SimulationEngine first = Simulations.create(config);
        first.run(1_234);
        Path file = dir.resolve("half.hjvm");
        archive.save(capture(first, List.of(), Set.of()), file);
        Investigation loaded = archive.load(file);
        SimulationEngine resumed = Simulations.resume(loaded.config(), loaded.timeline());
        resumed.run(3_000 - 1_234);

        assertThat(LogDigest.of(resumed.timeline().log())).isEqualTo(LogDigest.of(uninterrupted.timeline().log()));
    }

    @Test
    void aTamperedLogIsRejected(@TempDir Path dir) throws IOException {
        SimulationEngine engine = Simulations.create(SimulationConfig.defaults(1));
        engine.run(2_100);
        Path file = dir.resolve("orig.hjvm");
        archive.save(capture(engine, List.of(), Set.of()), file);

        Path tampered = dir.resolve("tampered.hjvm");
        rewrite(file, tampered, (name, bytes) -> {
            if (!name.equals(SessionArchive.EVENTS)) {
                return bytes;
            }
            // Quietly move someone one cell to the left in a single record.
            String text = new String(bytes, StandardCharsets.UTF_8);
            int at = text.indexOf("\"@type\":\"EntityMoved\"", text.length() / 3);
            int x = text.indexOf("\"to\":{\"x\":", at) + "\"to\":{\"x\":".length();
            int end = text.indexOf(',', x);
            int value = Integer.parseInt(text.substring(x, end));
            return (text.substring(0, x) + (value - 1) + text.substring(end)).getBytes(StandardCharsets.UTF_8);
        });

        assertThatThrownBy(() -> archive.load(tampered)).isInstanceOf(SessionFormatException.class)
                .hasMessageContaining("digest mismatch");
    }

    @Test
    void refusesFilesThatAreNotSessions(@TempDir Path dir) throws IOException {
        Path junk = dir.resolve("junk.hjvm");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(junk))) {
            zip.putNextEntry(new ZipEntry("hello.txt"));
            zip.write("hi".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertThatThrownBy(() -> archive.load(junk)).isInstanceOf(SessionFormatException.class);
    }

    @Test
    void theIncidentReportMentionsTheEssentials(@TempDir Path dir) throws IOException {
        SimulationEngine engine = Simulations.create(SimulationConfig.defaults(7).withAnomalyIntensity(3));
        engine.run(3_000);
        Investigation inv = capture(engine, List.of(InvestigationNote.write("session", 10, "Something in CAM-03.")),
                Set.of());
        String md = IncidentReport.render(inv, engine.world());
        assertThat(md).contains("0x0000000000000007", "## Personnel", "Something in CAM-03.", "Log digest");
        Path out = dir.resolve("reports/report.md");
        IncidentReport.write(inv, engine.world(), out);
        assertThat(Files.readString(out)).isEqualTo(md);
    }

    @Property(tries = 5)
    void archivesRoundTripForAnySeed(@ForAll @LongRange(min = 0, max = 1L << 40) long seed,
                                     @ForAll @IntRange(min = 1, max = 1_600) int ticks) throws IOException {
        SimulationEngine engine = Simulations.create(SimulationConfig.defaults(seed));
        engine.run(ticks);
        Path dir = Files.createTempDirectory("hjvm-prop");
        try {
            Path file = dir.resolve("s.hjvm");
            archive.save(capture(engine, List.of(), Set.of()), file);
            Investigation loaded = archive.load(file);
            assertThat(LogDigest.of(loaded.timeline().log())).isEqualTo(LogDigest.of(engine.timeline().log()));
        } finally {
            try (var files = Files.list(dir)) {
                for (Path p : files.toList()) {
                    Files.delete(p);
                }
            }
            Files.delete(dir);
        }
    }

    private interface EntryRewrite {
        byte[] apply(String name, byte[] bytes);
    }

    private static void rewrite(Path from, Path to, EntryRewrite rewrite) throws IOException {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(from));
             OutputStream raw = Files.newOutputStream(to);
             ZipOutputStream out = new ZipOutputStream(raw)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                out.putNextEntry(new ZipEntry(e.getName()));
                out.write(rewrite.apply(e.getName(), in.readAllBytes()));
                out.closeEntry();
            }
        }
    }
}
