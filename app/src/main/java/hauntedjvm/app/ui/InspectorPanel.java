package hauntedjvm.app.ui;

import hauntedjvm.app.render.Palette;
import hauntedjvm.app.session.Session;
import hauntedjvm.core.engine.OperatorCommand;
import hauntedjvm.core.entity.CameraFacet;
import hauntedjvm.core.entity.CameraStatus;
import hauntedjvm.core.entity.DoorFacet;
import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityId;
import hauntedjvm.core.entity.EntityKind;
import hauntedjvm.core.entity.EntityState;
import hauntedjvm.core.entity.ItemFacet;
import hauntedjvm.core.entity.LightMode;
import hauntedjvm.core.entity.MemoryKind;
import hauntedjvm.core.entity.MemoryTrace;
import hauntedjvm.core.entity.Mind;
import hauntedjvm.core.entity.ProcessFacet;
import hauntedjvm.core.entity.Relationship;
import hauntedjvm.core.entity.RoomFacet;
import hauntedjvm.core.entity.TerminalFacet;
import hauntedjvm.core.entity.Traits;
import hauntedjvm.core.event.EventLog;
import hauntedjvm.core.event.EventRecord;
import hauntedjvm.core.incident.AnomalyRecord;
import hauntedjvm.core.incident.Directive;
import hauntedjvm.core.incident.DetectedAnomaly;
import hauntedjvm.core.state.WorldView;
import hauntedjvm.core.time.FacilityClock;
import hauntedjvm.diagnostics.ThreadInspector;
import hauntedjvm.persistence.InvestigationNote;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

/**
 * Details of whatever is selected, with links to everything it touches.
 *
 * <p>Content is rebuilt only when the thing on display actually changed. Entities are immutable
 * records, so "changed" is a reference comparison.
 */
final class InspectorPanel extends ScrollPane {

    private final Operator operator;
    private final Session session;
    private final VBox content = new VBox(4);
    private final ActivityIndex activityIndex = new ActivityIndex();
    private Object shown;
    private Selection shownSelection;
    private int shownNotes = -1;

    InspectorPanel(Operator operator, Session session) {
        this.operator = operator;
        this.session = session;
        content.setPadding(new Insets(10, 12, 16, 12));
        setContent(content);
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        empty();
    }

    void refresh(Display d, Selection selection) {
        Object subject = switch (selection) {
            case Selection.OfEntity e -> d.world().entity(e.id());
            case Selection.OfAnomaly a -> d.world().incident().anomaly(a.id()).orElse(null);
            case Selection.OfEvent e -> e.seq() < d.frame().eventCount() ? d.frame().timeline().log().get(e.seq()) : null;
            default -> selection;
        };
        int notes = session.notes().size();
        if (subject == shown && selection.equals(shownSelection) && notes == shownNotes) {
            return;
        }
        shown = subject;
        shownSelection = selection;
        shownNotes = notes;
        content.getChildren().clear();
        switch (selection) {
            case Selection.None n -> empty();
            case Selection.OfEntity e -> {
                if (subject instanceof Entity entity) {
                    entity(d, entity);
                } else {
                    gone(e.id() + " does not exist at this point in the recording");
                }
            }
            case Selection.OfEvent e -> {
                if (subject instanceof EventRecord r) {
                    event(d, r);
                } else {
                    gone("event " + e.seq() + " is not in this timeline");
                }
            }
            case Selection.OfAnomaly a -> {
                if (subject instanceof AnomalyRecord r) {
                    anomaly(d, r);
                } else {
                    gone("no such record. It may have been withdrawn.");
                }
            }
            case Selection.OfThread t -> thread(t);
        }
        notesFor(selection);
    }

    // ---- sections ---------------------------------------------------------------------------

    private void empty() {
        content.getChildren().setAll(
                Widgets.label("NOTHING SELECTED", "inspector-title"),
                Widgets.label("Click someone on a camera or the floor plan, an event in the log,", "dim"),
                Widgets.label("or an anomaly in the list. Everything links to everything.", "dim"));
    }

    private void gone(String why) {
        content.getChildren().addAll(Widgets.label("NOT FOUND", "inspector-title"), Widgets.label(why, "dim"));
    }

    private void entity(Display d, Entity e) {
        WorldView w = d.world();
        header(e.kind() == EntityKind.UNKNOWN && !e.impersonating() ? "UNACCOUNTED" : e.name(),
                "ENTITY " + e.id() + " · " + e.kind()
                        + (e.hasMind() && e.mind().role() != null ? " · " + e.mind().role() : ""));
        content.getChildren().add(Widgets.label(e.uuid().toString(), "dim"));
        Widgets.KeyValues kv = new Widgets.KeyValues();
        kv.setPadding(new Insets(6, 0, 4, 0));
        if (e.impersonating()) {
            kv.set("answers to", EventText.n(w, e.identity()) + " " + e.identity(), "bad");
        }
        kv.set("state", e.state().name() + lastReason(d, e.id()), e.state().disturbed() || e.state() == EntityState.MISSING
                ? "warn" : null);
        switch (e.facet()) {
            case Mind m -> mind(d, e, m, kv);
            case ProcessFacet p -> process(e, p, kv);
            case DoorFacet door -> door(e, door, kv);
            case RoomFacet room -> room(w, e, room, kv);
            case CameraFacet cam -> camera(e, cam, kv);
            case ItemFacet item -> {
                kv.set("description", item.description());
                kv.set("catalogued in", w.map().roomCodeAt(item.home()) + " " + item.home());
                String now = e.position() == null ? "nowhere" : w.map().roomCodeAt(e.position()) + " " + e.position();
                kv.set("currently", now, e.position() != null && !e.position().equals(item.home()) ? "warn" : null);
                content.getChildren().add(kv);
            }
            case TerminalFacet t -> {
                kv.set("hostname", t.hostname());
                kv.set("room", t.roomCode());
                content.getChildren().add(kv);
            }
            default -> {
                kv.set("designation", e.name());
                content.getChildren().add(kv);
            }
        }
        activity(d, e.id());
    }

    private void mind(Display d, Entity e, Mind m, Widgets.KeyValues kv) {
        WorldView w = d.world();
        long t = w.tick();
        String tracker = e.reportedPosition() == null ? (e.tracked() ? "no signal" : "no badge")
                : w.map().roomCodeAt(e.reportedPosition()) + " " + e.reportedPosition();
        String visual = e.position() == null ? "not visible anywhere" : covered(w, w.roomOf(e))
                ? w.roomOf(e) + " " + e.position() : "no camera coverage";
        boolean disagree = e.position() != null && e.reportedPosition() != null && covered(w, w.roomOf(e))
                && !java.util.Objects.equals(w.roomOf(e), w.map().roomCodeAt(e.reportedPosition()));
        kv.set("location (tracker)", tracker, disagree ? "bad" : null);
        kv.set("location (cameras)", visual, disagree ? "bad" : null);
        if (m.goal() != null) {
            kv.set("heading", m.goal().roomCode() + " · " + m.goal().reason().name().toLowerCase()
                    + (m.goal().target() != null ? " · " + EventText.n(w, m.goal().target()) : ""));
        }
        if (e.forgotten()) {
            kv.set("remembered by", "nobody", "bad");
        }
        content.getChildren().add(kv);
        content.getChildren().addAll(
                meter("FEAR", m.fearAt(t), Palette.AMBER),
                meter("AWARENESS", m.awarenessAt(t), Palette.PAPER),
                meter("CORRUPTION", e.corruption(), Palette.WINE));
        if (m.role() != null) {
            Traits tr = m.traits();
            content.getChildren().add(Widgets.label(String.format(
                    "curiosity %.2f  fear %.2f  aggression %.2f  obedience %.2f  paranoia %.2f  memory %.2f  awareness %.2f",
                    tr.curiosity(), tr.fear(), tr.aggression(), tr.obedience(), tr.paranoia(), tr.memoryStrength(),
                    tr.awareness()), "dim"));
            ((Label) content.getChildren().getLast()).setWrapText(true);
        }
        HBox actions = new HBox(6,
                Widgets.button("FOLLOW", () -> operator.follow(e.id())),
                Widgets.button("TRACE", () -> operator.toggleTrace(e.id())),
                Widgets.button("NOTE", () -> operator.writeNote("entity:" + e.id())));
        actions.setPadding(new Insets(6, 0, 2, 0));
        content.getChildren().add(actions);

        content.getChildren().add(Widgets.sectionTitle("MEMORIES (" + m.memories().size() + ")", "sim", null));
        List<MemoryTrace> memories = new ArrayList<>(m.memories());
        memories.sort(Comparator.comparingLong(MemoryTrace::tick).reversed());
        if (memories.isEmpty()) {
            content.getChildren().add(Widgets.label("nothing yet", "dim"));
        }
        for (MemoryTrace mem : memories) {
            content.getChildren().add(memoryRow(d, m, mem, t));
        }
        if (!m.relationships().isEmpty()) {
            content.getChildren().add(Widgets.sectionTitle("RELATIONSHIPS", "sim", null));
            for (Relationship r : m.relationships()) {
                Label link = Widgets.label(String.format("%-10s trust %.2f  familiarity %.2f", EventText.n(w, r.other()),
                        r.trust(), r.familiarity()), "kv-value", "link");
                link.setOnMouseClicked(ev -> operator.select(new Selection.OfEntity(r.other())));
                content.getChildren().add(link);
            }
        }
    }

    private Node memoryRow(Display d, Mind m, MemoryTrace mem, long now) {
        boolean future = mem.tick() > now;
        String kind = switch (mem.kind()) {
            case SAW_ENTITY -> "SAW";
            case SAW_ANOMALY -> "WITNESSED";
            case HEARD -> "HEARD";
            case OBJECT_LOCATION -> "OBJECT";
            case PREVIOUS_TIMELINE -> "RESIDUE";
        };
        Label time = Widgets.label(FacilityClock.format(mem.tick()) + "  " + kind
                + String.format("  %.0f%%", mem.strengthAt(now, m.traits().memoryHalfLife()) * 100), "memory-time");
        if (future || mem.kind() == MemoryKind.PREVIOUS_TIMELINE) {
            time.setTextFill(Palette.RED);
            time.setText(time.getText() + "  FROM A TIMELINE THAT DID NOT HAPPEN");
        }
        Label note = Widgets.label(mem.note(), "memory-note");
        note.setWrapText(true);
        VBox row = new VBox(1, time, note);
        row.getStyleClass().add("memory-row");
        EventLog log = d.frame().timeline().log();
        if (mem.sourceSeq() >= 0 && mem.sourceSeq() < d.frame().eventCount()) {
            note.getStyleClass().add("link");
            note.setOnMouseClicked(ev -> operator.select(new Selection.OfEvent(mem.sourceSeq())));
        } else if (mem.id() >= 0 && mem.id() < log.size()) {
            note.getStyleClass().add("link");
            note.setOnMouseClicked(ev -> operator.select(new Selection.OfEvent(mem.id())));
        }
        return row;
    }

    private void process(Entity e, ProcessFacet p, Widgets.KeyValues kv) {
        content.getChildren().add(Widgets.tag("SIMULATED PROCESS — NOT AN OS PROCESS", "sim"));
        kv.set("command", p.command());
        kv.set("pid", String.valueOf(p.claimedPid()) + (p.claimedPid() != p.pid() ? "  (registered as " + p.pid() + ")"
                : ""), p.claimedPid() != p.pid() ? "bad" : null);
        kv.set("host", p.host());
        kv.set("sim heap", String.format("%.2f MB", p.heapBytes() / 1048576.0));
        kv.set("started", FacilityClock.format(p.startedTick()));
        if (p.terminated()) {
            kv.set("terminated", FacilityClock.format(p.terminatedTick()) + " (" + p.stopReason() + ")", "warn");
            if (p.lastActivity() > p.terminatedTick()) {
                kv.set("last activity", FacilityClock.format(p.lastActivity()) + " — after termination", "bad");
            }
        }
        kv.set("restarts", String.valueOf(p.restarts()));
        content.getChildren().add(kv);
        boolean running = e.state() == EntityState.RUNNING;
        HBox actions = new HBox(6,
                running ? Widgets.button("TERMINATE", () -> operator.command(new OperatorCommand.TerminateProcess(e.id())),
                        "danger")
                        : Widgets.button("RESTART", () -> operator.command(new OperatorCommand.RestartProcess(e.id()))),
                Widgets.button("NOTE", () -> operator.writeNote("entity:" + e.id())));
        actions.setDisable(operator.reviewing());
        content.getChildren().add(actions);
        content.getChildren().add(Widgets.label(effect(p.command()), "dim"));
    }

    private static String effect(String command) {
        return switch (command) {
            case "badge-trackd" -> "Stopping it freezes every badge position on the floor plan.";
            case "cam-mux" -> "Stopping it fills every camera feed with interference.";
            case "lumen-ctl" -> "Stopping it drops the facility to emergency lighting.";
            case "watchdog" -> "Restarts other processes that stop. Judges liveness by activity.";
            case "incident-log" -> "Writes the incident log. Something else writes it when this is down.";
            default -> "No facility function depends on it.";
        };
    }

    private void door(Entity e, DoorFacet d, Widgets.KeyValues kv) {
        kv.set("status", d.sealed() ? "SEALED" : (d.open() ? "OPEN" : "CLOSED") + (d.locked() ? ", LOCKED" : ""),
                d.locked() ? "warn" : null);
        content.getChildren().add(kv);
        if (!d.sealed()) {
            HBox actions = new HBox(6, Widgets.button(d.locked() ? "UNLOCK" : "LOCK",
                    () -> operator.command(new OperatorCommand.SetDoorLock(d.code(), !d.locked()))));
            actions.setDisable(operator.reviewing());
            content.getChildren().add(actions);
        }
    }

    private void room(WorldView w, Entity e, RoomFacet r, Widgets.KeyValues kv) {
        kv.set("label", r.label());
        kv.set("lights", r.light() + (r.light() != r.normal() ? " (normally " + r.normal() + ")" : ""),
                r.light().dark() ? "warn" : null);
        List<String> people = w.occupants(r.code()).stream()
                .filter(o -> o.kind() == EntityKind.PERSON).map(Entity::name).toList();
        kv.set("occupants", people.isEmpty() ? "none visible to tracking" : String.join(", ", people));
        content.getChildren().add(kv);
        FlowPane actions = new FlowPane(6, 6);
        for (LightMode mode : new LightMode[] {LightMode.ON, LightMode.DIM, LightMode.OFF}) {
            actions.getChildren().add(Widgets.button("LIGHTS " + mode,
                    () -> operator.command(new OperatorCommand.SetLight(r.code(), mode))));
        }
        actions.getChildren().add(Widgets.button("GATHER STAFF HERE",
                () -> operator.command(new OperatorCommand.IssueDirective(Directive.Type.GATHER, r.code()))));
        actions.setDisable(operator.reviewing() || !r.listed());
        content.getChildren().add(actions);
    }

    private void camera(Entity e, CameraFacet c, Widgets.KeyValues kv) {
        kv.set("covers", c.roomCode());
        kv.set("status", c.status() + (c.status() == CameraStatus.LOOPING ? " (" + c.timelineOffset() + "s behind)" : ""),
                c.status() == CameraStatus.ONLINE ? null : "warn");
        content.getChildren().add(kv);
        content.getChildren().add(Widgets.button("VIEW FEED", () -> operator.view(new Feed.Camera(c.code()))));
    }

    private void event(Display d, EventRecord r) {
        header("EVENT " + r.seq(), r.event().type() + " · " + EventText.group(r.event()));
        Label text = Widgets.label(EventText.describe(r, d.world()), "memory-note");
        text.setWrapText(true);
        text.setTextFill(Palette.event(r.event()));
        content.getChildren().add(text);
        Widgets.KeyValues kv = new Widgets.KeyValues();
        kv.setPadding(new Insets(6, 0, 4, 0));
        kv.set("recorded", FacilityClock.format(r.tick()) + " (tick " + r.tick() + ")");
        if (r.misdated()) {
            kv.set("claims", FacilityClock.format(r.reportedTick()) + " (tick " + r.reportedTick() + ")", "bad");
        }
        for (RecordComponent c : r.event().getClass().getRecordComponents()) {
            try {
                Object value = c.getAccessor().invoke(r.event());
                kv.set(c.getName(), value == null ? "—" : abbreviate(String.valueOf(value)));
            } catch (ReflectiveOperationException ex) {
                kv.set(c.getName(), "?");
            }
        }
        content.getChildren().add(kv);
        FlowPane subjects = new FlowPane(6, 4);
        for (EntityId id : r.event().subjects()) {
            Label link = Widgets.label(EventText.n(d.world(), id) + " " + id, "kv-value", "link");
            link.setOnMouseClicked(ev -> operator.select(new Selection.OfEntity(id)));
            subjects.getChildren().add(link);
        }
        content.getChildren().add(subjects);
        content.getChildren().add(new HBox(6,
                Widgets.button("JUMP TO " + FacilityClock.format(r.tick()), () -> operator.jumpTo(r.tick(), r.seq())),
                Widgets.button("NOTE", () -> operator.writeNote("event:" + r.seq()))));
    }

    private void anomaly(Display d, AnomalyRecord record) {
        DetectedAnomaly a = record.anomaly();
        header(a.category() + " / " + a.severity(), "ANOMALY · " + record.status()
                + " · logged " + FacilityClock.format(a.tick()));
        if (record.status() == AnomalyRecord.Status.RETRACTED) {
            content.getChildren().add(Widgets.label("RECORD WITHDRAWN AT " + FacilityClock.format(record.statusTick()),
                    "alarm"));
            return;
        }
        Label summary = Widgets.label(a.summary(), "memory-note");
        summary.setWrapText(true);
        summary.setTextFill(Palette.category(a.category()));
        content.getChildren().add(summary);
        Widgets.KeyValues kv = new Widgets.KeyValues();
        kv.setPadding(new Insets(6, 0, 4, 0));
        kv.set("room", a.roomCode() == null ? "—" : a.roomCode());
        kv.set("condition", a.key());
        content.getChildren().add(kv);
        if (!a.subjects().isEmpty()) {
            content.getChildren().add(Widgets.sectionTitle("SUBJECTS", "sim", null));
            for (EntityId id : a.subjects()) {
                Label link = Widgets.label(EventText.n(d.world(), id) + " " + id, "kv-value", "link");
                link.setOnMouseClicked(ev -> operator.select(new Selection.OfEntity(id)));
                content.getChildren().add(link);
            }
        }
        if (!a.evidence().isEmpty()) {
            content.getChildren().add(Widgets.sectionTitle("EVIDENCE", "sim", null));
            for (long seq : a.evidence()) {
                if (seq >= d.frame().eventCount()) {
                    continue;
                }
                EventRecord r = d.frame().timeline().log().get(seq);
                Label link = Widgets.label(FacilityClock.format(r.tick()) + "  " + EventText.describe(r, d.world()),
                        "kv-value", "link");
                link.setWrapText(true);
                link.setOnMouseClicked(ev -> operator.jumpTo(r.tick(), seq));
                content.getChildren().add(link);
            }
        }
        content.getChildren().add(new HBox(6,
                Widgets.button("JUMP TO " + FacilityClock.format(a.tick()), () -> operator.jumpTo(a.tick(), -1)),
                Widgets.button("NOTE", () -> operator.writeNote("anomaly:" + a.id()))));
    }

    private void thread(Selection.OfThread t) {
        header(t.name(), "JVM THREAD " + t.threadId());
        content.getChildren().add(Widgets.tag("REAL — A THREAD OF THIS JVM", "real"));
        ThreadInspector.ThreadView view = new ThreadInspector().threads().stream()
                .filter(v -> v.id() == t.threadId()).findFirst().orElse(null);
        Widgets.KeyValues kv = new Widgets.KeyValues();
        kv.setPadding(new Insets(6, 0, 4, 0));
        if (view == null) {
            kv.set("status", "no longer running");
        } else {
            kv.set("state", view.state().name());
            kv.set("daemon", String.valueOf(view.daemon()));
            kv.set("cpu time", view.cpuNanos() < 0 ? "unsupported" : String.format("%.1f ms", view.cpuNanos() / 1e6));
            kv.set("blocked", String.valueOf(view.blockedCount()));
            kv.set("waited", String.valueOf(view.waitedCount()));
            kv.set("lock", view.lockName() == null ? "—" : view.lockName());
        }
        content.getChildren().add(kv);
    }

    private void notesFor(Selection selection) {
        List<InvestigationNote> notes = session.notes().stream().filter(n -> n.targetRef().equals(selection.ref())).toList();
        if (notes.isEmpty() || selection instanceof Selection.None) {
            return;
        }
        content.getChildren().add(Widgets.sectionTitle("YOUR NOTES", "fiction", null));
        for (InvestigationNote n : notes) {
            Label l = Widgets.label("“" + n.text() + "”  — " + FacilityClock.format(n.tick()), "memory-note");
            l.setWrapText(true);
            l.setTextFill(Palette.AMBER);
            content.getChildren().add(l);
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private void header(String title, String kind) {
        content.getChildren().addAll(Widgets.label(title, "inspector-title"), Widgets.label(kind, "inspector-kind"));
    }

    private Node meter(String name, double value, Color color) {
        Widgets.Meter m = new Widgets.Meter(170);
        m.update(value, color);
        Label l = Widgets.label(String.format("%-11s %3.0f%%", name, value * 100), "kv-key");
        HBox row = new HBox(8, l, m);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private ActivityIndex.Activity activityOf(Display d, EntityId id) {
        EventLog log = d.frame().timeline().log();
        long limit = Math.min(d.frame().eventCount(), log.firstSeqAfterTick(d.tick()));
        return activityIndex.lookup(d.frame().timeline(), limit, id);
    }

    private void activity(Display d, EntityId id) {
        List<EventRecord> found = activityOf(d, id).recent().reversed();
        content.getChildren().add(Widgets.sectionTitle("RECENT ACTIVITY", "sim", null));
        if (found.isEmpty()) {
            content.getChildren().add(Widgets.label("nothing notable recently", "dim"));
        }
        for (EventRecord r : found) {
            Label l = Widgets.label(FacilityClock.format(r.tick()) + "  " + EventText.describe(r, d.world()), "kv-value",
                    "link");
            l.setWrapText(true);
            l.setOnMouseClicked(ev -> operator.select(new Selection.OfEvent(r.seq())));
            content.getChildren().add(l);
        }
    }

    private String lastReason(Display d, EntityId id) {
        String reason = activityOf(d, id).lastReason();
        return reason == null ? "" : "  · " + reason;
    }

    private static boolean covered(WorldView w, String room) {
        if (room == null) {
            return false;
        }
        return w.ofKind(EntityKind.CAMERA).stream().anyMatch(c -> ((CameraFacet) c.facet()).roomCode().equals(room)
                && ((CameraFacet) c.facet()).status() != CameraStatus.OFFLINE);
    }

    private static String abbreviate(String s) {
        return s.length() > 160 ? s.substring(0, 159) + "…" : s;
    }
}
