package hauntedjvm.app.render;

import hauntedjvm.core.entity.EntityState;
import hauntedjvm.core.event.AnomalyEvent;
import hauntedjvm.core.event.CommunicationEvent;
import hauntedjvm.core.event.EntityEvent;
import hauntedjvm.core.event.EnvironmentEvent;
import hauntedjvm.core.event.OperatorEvent;
import hauntedjvm.core.event.ProcessEvent;
import hauntedjvm.core.event.SimEvent;
import hauntedjvm.core.incident.AnomalyCategory;
import javafx.scene.paint.Color;

/**
 * The workstation's colours. Green phosphor for the ordinary, amber for attention, a single red
 * for alarm, and paper white for what is looking back. Nothing else gets a hue of its own.
 */
public final class Palette {

    public static final Color BACKGROUND = Color.web("#060807");
    public static final Color PANEL = Color.web("#0b0e0d");
    public static final Color PANEL_RAISED = Color.web("#101513");
    public static final Color LINE = Color.web("#1d2823");
    public static final Color GRID = Color.web("#12201a");
    public static final Color PHOSPHOR = Color.web("#8fd694");
    public static final Color PHOSPHOR_DIM = Color.web("#4b7552");
    public static final Color PHOSPHOR_FAINT = Color.web("#1f3a27");
    public static final Color AMBER = Color.web("#e3a857");
    public static final Color AMBER_DIM = Color.web("#7d5a2c");
    public static final Color RED = Color.web("#d0584a");
    public static final Color PAPER = Color.web("#e6e2d6");
    public static final Color MUTED = Color.web("#6f7872");
    public static final Color TEAL = Color.web("#7fb7b0");
    public static final Color LILAC = Color.web("#a89bd0");
    public static final Color WINE = Color.web("#c0587a");
    public static final Color KHAKI = Color.web("#c7d58a");

    private Palette() {
    }

    public static Color state(EntityState state) {
        return switch (state) {
            case NORMAL, RUNNING, ONLINE, PRESENT -> PHOSPHOR;
            case SUSPICIOUS -> KHAKI;
            case AFRAID, BLOCKED, DEGRADED -> AMBER;
            case AVOIDING -> Color.web("#d98c4a");
            case INVESTIGATING -> TEAL;
            case FOLLOWING -> Color.web("#a6d3a4");
            case ECHOING -> LILAC;
            case CORRUPTED -> WINE;
            case AWARE -> PAPER;
            case MISSING, TERMINATED, OFFLINE -> MUTED;
            case DORMANT, WANDERING, STALKING, MIMICKING -> Color.web("#2a2a2a");
        };
    }

    public static Color category(AnomalyCategory category) {
        return switch (category) {
            case TEMPORAL -> TEAL;
            case SPATIAL -> AMBER;
            case BEHAVIORAL -> KHAKI;
            case MEMORY -> LILAC;
            case SYSTEM -> PHOSPHOR;
            case VISUAL -> Color.web("#d9d4c4");
            case COMMUNICATION -> Color.web("#e0b878");
            case IDENTITY -> RED;
            case OBSERVATION -> PAPER;
        };
    }

    public static Color event(SimEvent event) {
        return switch (event) {
            case AnomalyEvent.AnomalyDetected d -> category(d.anomaly().category());
            case AnomalyEvent.AnomalyEscalated e -> RED;
            case AnomalyEvent e -> MUTED;
            case EntityEvent.EntityStateChanged e -> state(e.to());
            case EntityEvent e -> PHOSPHOR_DIM;
            case ProcessEvent e -> Color.web("#6fa87a");
            case EnvironmentEvent e -> Color.web("#b9b3a0");
            case CommunicationEvent.MessageSent m when m.sender() == null -> AMBER;
            case CommunicationEvent e -> Color.web("#c9b58f");
            case OperatorEvent e -> TEAL;
        };
    }

    /** Six steps from calm green to alarm red, for the escalation indicator. */
    public static Color level(int level) {
        return switch (level) {
            case 0 -> PHOSPHOR_DIM;
            case 1 -> KHAKI;
            case 2 -> AMBER;
            case 3 -> Color.web("#d98c4a");
            case 4 -> PAPER;
            default -> RED;
        };
    }

    public static String hex(Color c) {
        return String.format("#%02x%02x%02x", (int) Math.round(c.getRed() * 255), (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255));
    }
}
