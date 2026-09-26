package hauntedjvm.core.timeline;

import hauntedjvm.core.event.EventLog;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A fingerprint of an event log. Two runs with the same digest recorded the same history;
 * {@code --headless} prints it so reproducibility can be checked from a shell.
 *
 * <p>Record {@code toString} is used as the canonical form. It covers every component, and the
 * formatting of records, enums and doubles is specified by the JDK, which is what a
 * cross-machine fingerprint needs.
 */
public final class LogDigest {

    private LogDigest() {
    }

    public static String of(EventLog log) {
        return of(log, log.size());
    }

    public static String of(EventLog log, long upToSeq) {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java SE specification", e);
        }
        log.forEach(0, upToSeq, r -> {
            sha.update(r.toString().getBytes(StandardCharsets.UTF_8));
            sha.update((byte) '\n');
        });
        return HexFormat.of().formatHex(sha.digest());
    }
}
