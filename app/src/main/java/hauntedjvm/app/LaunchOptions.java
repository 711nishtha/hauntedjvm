package hauntedjvm.app;

import hauntedjvm.core.config.SimulationConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;

/**
 * Command-line options. Parsed by hand: six flags do not justify a dependency.
 *
 * @param seed      explicit seed, or empty for a fresh random one
 * @param speed     playback multiplier relative to 1x
 * @param ticks     headless run length
 * @param load      session file to open at startup
 * @param save      headless: where to write the session
 * @param report    headless: where to write the Markdown incident report
 */
public record LaunchOptions(
        OptionalLong seed,
        int persons,
        double speed,
        boolean headless,
        long ticks,
        boolean debug,
        boolean mute,
        Path load,
        Path save,
        Path report,
        boolean help,
        boolean version) {

    public static final String USAGE = """
            Usage: hauntedjvm [options]

              --seed <value>            seed for the night (decimal, 0x-hex, or any word)
              --entities <n>            night-shift headcount (1-%d, default %d)
              --simulation-speed <x>    playback multiplier, 0.25-64 (default 1)
              --load <file.hjvm>        open a saved investigation
              --mute                    start with sound off
              --debug                   verbose logs and the director's hidden events
              --headless                run without a window and print the incident log
                --ticks <n>             headless run length (default 12000, about 3h20m facility time)
                --save <file.hjvm>      headless: save the session when done
                --report <file.md>      headless: write an incident report when done
              --help                    this text
              --version                 print the version
            """.formatted(SimulationConfig.MAX_PERSONS, SimulationConfig.DEFAULT_PERSONS);

    public static LaunchOptions parse(String... args) {
        OptionalLong seed = OptionalLong.empty();
        int persons = SimulationConfig.DEFAULT_PERSONS;
        double speed = 1;
        boolean headless = false;
        long ticks = 12_000;
        boolean debug = false;
        boolean mute = false;
        Path load = null;
        Path save = null;
        Path report = null;
        boolean help = false;
        boolean version = false;
        List<String> rest = new ArrayList<>(List.of(args));
        while (!rest.isEmpty()) {
            String flag = rest.removeFirst();
            switch (flag) {
                case "--seed" -> seed = OptionalLong.of(parseSeed(value(flag, rest)));
                case "--entities" -> persons = (int) parseRange(flag, value(flag, rest), 1, SimulationConfig.MAX_PERSONS);
                case "--simulation-speed" -> speed = parseDouble(flag, value(flag, rest), 0.25, 64);
                case "--headless" -> headless = true;
                case "--ticks" -> ticks = parseRange(flag, value(flag, rest), 1, 10_000_000);
                case "--debug" -> debug = true;
                case "--mute" -> mute = true;
                case "--load" -> load = Path.of(value(flag, rest));
                case "--save" -> save = Path.of(value(flag, rest));
                case "--report" -> report = Path.of(value(flag, rest));
                case "--help", "-h" -> help = true;
                case "--version" -> version = true;
                default -> throw new IllegalArgumentException("unknown option: " + flag);
            }
        }
        if (!headless && (save != null || report != null)) {
            throw new IllegalArgumentException("--save and --report only apply with --headless; use the menu in the app");
        }
        return new LaunchOptions(seed, persons, speed, headless, ticks, debug, mute, load, save, report, help, version);
    }

    /**
     * Seeds are numbers, but people like to type words. {@code --seed archive} is as valid as
     * {@code --seed 0x1F}; words are hashed with 64-bit FNV-1a so the mapping never changes.
     */
    static long parseSeed(String text) {
        String s = text.strip();
        try {
            if (s.toLowerCase(Locale.ROOT).startsWith("0x")) {
                return Long.parseUnsignedLong(s.substring(2), 16);
            }
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            long h = 0xcbf29ce484222325L;
            for (byte b : s.toUpperCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)) {
                h ^= b & 0xff;
                h *= 0x100000001b3L;
            }
            return h;
        }
    }

    private static String value(String flag, List<String> rest) {
        if (rest.isEmpty() || rest.getFirst().startsWith("--")) {
            throw new IllegalArgumentException(flag + " needs a value");
        }
        return rest.removeFirst();
    }

    private static long parseRange(String flag, String v, long min, long max) {
        try {
            long n = Long.parseLong(v.strip());
            if (n < min || n > max) {
                throw new IllegalArgumentException(flag + " must be between " + min + " and " + max);
            }
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(flag + " expects a whole number, got '" + v + "'");
        }
    }

    private static double parseDouble(String flag, String v, double min, double max) {
        try {
            double d = Double.parseDouble(v.strip().replace("x", ""));
            if (!(d >= min && d <= max)) {
                throw new IllegalArgumentException(flag + " must be between " + min + " and " + max);
            }
            return d;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(flag + " expects a number, got '" + v + "'");
        }
    }
}
