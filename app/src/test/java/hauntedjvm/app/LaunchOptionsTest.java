package hauntedjvm.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class LaunchOptionsTest {

    @Test
    void defaults() {
        LaunchOptions o = LaunchOptions.parse();
        assertThat(o.seed()).isEmpty();
        assertThat(o.persons()).isEqualTo(16);
        assertThat(o.headless()).isFalse();
        assertThat(o.speed()).isEqualTo(1.0);
    }

    @Test
    void seedsAcceptDecimalHexAndWords() {
        assertThat(LaunchOptions.parseSeed("42")).isEqualTo(42);
        assertThat(LaunchOptions.parseSeed("0xFF")).isEqualTo(255);
        assertThat(LaunchOptions.parseSeed("0xFFFFFFFFFFFFFFFF")).isEqualTo(-1);
        assertThat(LaunchOptions.parseSeed("archive")).isEqualTo(LaunchOptions.parseSeed("ARCHIVE"));
        assertThat(LaunchOptions.parseSeed("archive")).isNotEqualTo(LaunchOptions.parseSeed("archives"));
    }

    @Test
    void parsesTheDocumentedFlags() {
        LaunchOptions o = LaunchOptions.parse("--seed", "7", "--entities", "40", "--simulation-speed", "4",
                "--headless", "--ticks", "100", "--debug", "--mute");
        assertThat(o.seed()).hasValue(7);
        assertThat(o.persons()).isEqualTo(40);
        assertThat(o.speed()).isEqualTo(4.0);
        assertThat(o.headless()).isTrue();
        assertThat(o.ticks()).isEqualTo(100);
        assertThat(o.debug()).isTrue();
        assertThat(o.mute()).isTrue();
    }

    @Test
    void rejectsNonsense() {
        assertThatThrownBy(() -> LaunchOptions.parse("--entities", "0")).hasMessageContaining("between");
        assertThatThrownBy(() -> LaunchOptions.parse("--entities")).hasMessageContaining("needs a value");
        assertThatThrownBy(() -> LaunchOptions.parse("--simulation-speed", "fast")).hasMessageContaining("number");
        assertThatThrownBy(() -> LaunchOptions.parse("--haunt")).hasMessageContaining("unknown option");
        assertThatThrownBy(() -> LaunchOptions.parse("--save", "x.hjvm")).hasMessageContaining("--headless");
    }

    @Test
    void headlessRunsAreReproducible() throws Exception {
        String first = headless("--headless", "--seed", "11", "--ticks", "1500");
        String second = headless("--headless", "--seed", "11", "--ticks", "1500");
        assertThat(digest(first)).isEqualTo(digest(second)).hasSize(64);
        assertThat(first).contains("END OF RECORDING", "seed 0x000000000000000B");
    }

    private static String headless(String... args) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        LaunchOptions o = LaunchOptions.parse(args);
        new HeadlessRunner(o, new PrintStream(buffer, true, StandardCharsets.UTF_8)).run(o.seed().orElseThrow());
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static String digest(String output) {
        return output.lines().filter(l -> l.startsWith("log digest")).findFirst().orElseThrow().split("\\s+")[2];
    }
}
