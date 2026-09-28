package hauntedjvm.app.ui;

import hauntedjvm.app.render.CameraRenderer;
import hauntedjvm.app.render.FloorplanRenderer;
import hauntedjvm.app.render.Motion;
import hauntedjvm.app.render.Palette;
import hauntedjvm.app.render.PostFx;
import hauntedjvm.app.render.RenderInput;
import hauntedjvm.app.render.SystemRenderer;
import hauntedjvm.app.runtime.Frame;
import hauntedjvm.app.runtime.Speed;
import hauntedjvm.app.session.Session;
import hauntedjvm.audio.Cue;
import hauntedjvm.core.engine.OperatorCommand;
import hauntedjvm.core.entity.CameraFacet;
import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityId;
import hauntedjvm.core.entity.EntityKind;
import hauntedjvm.core.event.AnomalyEvent;
import hauntedjvm.core.event.EventRecord;
import hauntedjvm.core.incident.EscalationLevel;
import hauntedjvm.core.state.WorldSnapshot;
import hauntedjvm.core.state.WorldState;
import hauntedjvm.core.state.WorldView;
import hauntedjvm.core.time.FacilityClock;
import hauntedjvm.core.timeline.ReplayCursor;
import hauntedjvm.core.timeline.Timeline;
import hauntedjvm.core.world.FacilityMap;
import java.util.HashSet;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.scene.Parent;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * The investigation workstation for one session: layout, the per-frame loop, and every
 * operator action.
 *
 * <p>The frame loop runs on the JavaFX pulse. It reads the latest frame the simulation thread
 * published (a lock-free handoff), decides which world to show (live, or reconstructed at the
 * playhead), and renders. Rendering never waits for the simulation, and the simulation never
 * waits for rendering; interpolation smooths over the difference in rates.
 */
public final class Workstation implements Operator, AutoCloseable {

    private static final long PANEL_PERIOD = 250_000_000L;
    private static final long STRIP_PERIOD = 50_000_000L;

    private final AppServices app;
    private final Session session;
    private final ViewState view;
    private final FacilityMap map = FacilityMap.facility07();
    private final Motion motion = new Motion();
    private final PostFx fx = new PostFx();
    private final SoundDirector sound;
    private final StackPane root = new StackPane();
    private final Overlays overlays = new Overlays();
    private final HeaderBar header;
    private final TransportBar transport;
    private final MainView mainView;
    private final FeedRail rail;
    private final TimelineStrip strip;
    private final EventLogPanel log;
    private final TelemetryPanel telemetryPanel;
    private final InspectorPanel inspector;
    private final AnomalyPanel anomalies;
    private final NotesPanel notes;
    private final SidePanel side;
    private final SessionDialogs dialogs;
    private final AnimationTimer timer;

    private Speed liveSpeed;
    private WorldSnapshot liveSnapshot;
    private WorldState liveWorld;
    private ReplayCursor cursor;
    private Timeline eventsTimeline;
    private long eventsSeen;
    private long lastPanel;
    private long lastStrip;
    private long lastPulse;
    private double reviewCarry;
    private double fps;
    private String followRoom;
    private boolean reportedError;
    private boolean ready;

    public Workstation(AppServices app, Session session, Speed initialSpeed) {
        this.app = app;
        this.session = session;
        this.view = new ViewState(app.debug());
        this.liveSpeed = initialSpeed;
        this.sound = new SoundDirector(app.audio());
        CameraRenderer cameraRenderer = new CameraRenderer(Fonts.display(30), Fonts.mono(11));
        FloorplanRenderer floorplanRenderer = new FloorplanRenderer(Fonts.monoBold(12), Fonts.mono(9.5));
        SystemRenderer systemRenderer = new SystemRenderer(Fonts.monoBold(13), Fonts.mono(10.5), Fonts.display(26));

        header = new HeaderBar(new HeaderBar.Actions(this::requestNew, this::save, this::load, this::export,
                overlays::help, this::toggleSound));
        header.setSession(session.config());
        header.setSound(!app.audio().muted());
        transport = new TransportBar(new TransportBar.Actions(() -> scrub(-60), () -> scrub(-10), this::playPause,
                this::step, () -> scrub(10), () -> changeSpeed(false), () -> changeSpeed(true), this::live,
                this::resumeHere));
        mainView = new MainView(this, cameraRenderer, floorplanRenderer, systemRenderer, fx);
        rail = new FeedRail(this::view, this::select, cameraRenderer, floorplanRenderer, systemRenderer, fx);
        strip = new TimelineStrip(t -> jumpTo(t, -1));
        log = new EventLogPanel(this, app.debug());
        telemetryPanel = new TelemetryPanel(app.telemetry(), this::select);
        inspector = new InspectorPanel(this, session);
        anomalies = new AnomalyPanel(this, session);
        notes = new NotesPanel(session, this);
        side = new SidePanel(telemetryPanel, inspector, anomalies, notes);
        dialogs = new SessionDialogs(app, session, overlays, this::liveWorld);

        BorderPane monitor = new BorderPane(mainView);
        monitor.setBottom(transport);
        SplitPane centre = new SplitPane(rail, monitor, side);
        centre.setDividerPositions(0.14, 0.72);
        SplitPane.setResizableWithParent(rail, false);
        SplitPane.setResizableWithParent(side, false);
        VBox bottom = new VBox(strip, log);
        VBox.setVgrow(log, javafx.scene.layout.Priority.ALWAYS);
        SplitPane vertical = new SplitPane(centre, bottom);
        vertical.setOrientation(Orientation.VERTICAL);
        vertical.setDividerPositions(0.74);
        BorderPane layout = new BorderPane(vertical);
        layout.setTop(header);
        root.getChildren().addAll(layout, overlays);

        view.feed().addListener((o, a, b) -> motion.reset());
        view.reviewTick().addListener((o, a, b) -> {
            if ((a.longValue() == ViewState.LIVE) != (b.longValue() == ViewState.LIVE)) {
                motion.reset();
            }
        });
        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                pulse(now);
            }
        };
        timer.start();
    }

    /** Smoothed render rate, for tooling and the telemetry panel. */
    double fps() {
        return fps;
    }

    public Parent root() {
        return root;
    }

    /** Plays the boot sequence; the simulation starts once the operator dismisses the briefing. */
    public void boot() {
        session.runner().setPaused(true);
        overlays.boot(session.config().seedHex(), session.config().persons(), () -> {
            session.runner().setPaused(false);
            ready = true;
        });
    }

    public void skipBoot() {
        ready = true;
    }

    // ---- frame loop --------------------------------------------------------------------------

    private void pulse(long now) {
        double dt = lastPulse == 0 ? 0 : (now - lastPulse) / 1e9;
        lastPulse = now;
        if (dt > 0) {
            fps = fps * 0.9 + (1 / dt) * 0.1;
        }
        Frame frame = session.runner().latest();
        if (frame.snapshot() != liveSnapshot) {
            liveSnapshot = frame.snapshot();
            liveWorld = WorldState.restore(map, liveSnapshot);
        }
        WorldView world = liveWorld;
        double alpha = 1;
        if (view.reviewing()) {
            if (view.playing().get()) {
                reviewCarry += dt * Speed.BASE_TICKS_PER_SECOND * view.reviewSpeed().get().factor();
                long whole = (long) reviewCarry;
                if (whole > 0) {
                    reviewCarry -= whole;
                    long next = view.reviewTick().get() + whole;
                    if (next >= frame.status().tick()) {
                        live();
                    } else {
                        view.reviewTick().set(next);
                    }
                }
                alpha = reviewCarry;
            }
            if (view.reviewing()) {
                if (cursor == null || cursor.timeline() != frame.timeline()) {
                    cursor = new ReplayCursor(map, frame.timeline());
                }
                world = cursor.seek(view.reviewTick().get());
            }
        } else if (!frame.status().paused()) {
            alpha = (now - frame.publishedNanos()) / (1e9 / Math.max(0.1, frame.status().targetTps()));
        }
        Display d = new Display(world, liveWorld, frame, view.reviewing());
        motion.observe(world);
        EntityId selected = view.selection().get() instanceof Selection.OfEntity e ? e.id() : null;
        RenderInput in = new RenderInput(world, motion, alpha, now, selected, view.reviewing(), view.debug(),
                new HashSet<>(view.traced()));
        mainView.render(d, view.feed().get(), in);
        rail.drawThumbnails(d, in);
        events(frame, now);
        sound.mood(d, view.feed().get(), now);
        if (now - lastStrip > STRIP_PERIOD) {
            lastStrip = now;
            strip.update(frame.timeline(), frame.eventCount(), frame.status().tick(),
                    view.reviewing() ? view.reviewTick().get() : -1);
        }
        if (now - lastPanel > PANEL_PERIOD) {
            lastPanel = now;
            panels(d);
        }
    }

    private void panels(Display d) {
        header.update(d, view.playing().get());
        transport.update(d, view.playing().get(), (d.review() ? view.reviewSpeed().get() : liveSpeed).label());
        rail.refresh(d, view.feed().get(), view.selection().get());
        telemetryPanel.setFps(fps);
        switch (side.current()) {
            case TELEMETRY -> telemetryPanel.refresh(d);
            case INSPECT -> inspector.refresh(d, view.selection().get());
            case ANOMALIES -> anomalies.refresh(d);
            case NOTES -> notes.refresh(d, view.selection().get());
            default -> throw new IllegalStateException("unknown tab " + side.current());
        }
        long limit = d.review() ? Math.min(d.frame().eventCount(), d.frame().timeline().log().firstSeqAfterTick(d.tick()))
                : d.frame().eventCount();
        log.refresh(d.frame().timeline(), limit, d.world());
        followSelected(d);
        if (d.frame().status().error() != null && !reportedError) {
            reportedError = true;
            overlays.toast("SIMULATION HALTED: " + d.frame().status().error(), "bad");
        }
    }

    /** Sounds, toasts and banners for what the live facility just recorded. */
    private void events(Frame frame, long now) {
        if (frame.timeline() != eventsTimeline) {
            eventsTimeline = frame.timeline();
            eventsSeen = frame.eventCount();
            return;
        }
        if (!ready) {
            eventsSeen = frame.eventCount();
            return;
        }
        long from = Math.max(eventsSeen, frame.eventCount() - 2_000);
        for (long s = from; s < frame.eventCount(); s++) {
            EventRecord r = frame.timeline().log().get(s);
            sound.onEvent(r, liveWorld, now);
            switch (r.event()) {
                case AnomalyEvent.AnomalyEscalated e -> overlays.banner("INCIDENT " + EscalationLevel.of(e.to()).label(),
                        Palette.level(e.to()));
                case AnomalyEvent.AnomalyDetected a when a.anomaly().severity() >= 3 ->
                        overlays.toast("ANOMALY " + a.anomaly().category() + ": " + a.anomaly().summary(), "warn");
                default -> {
                    // everything else is in the log
                }
            }
        }
        eventsSeen = frame.eventCount();
    }

    private void followSelected(Display d) {
        if (!view.follow().get() || d.review() || !(view.selection().get() instanceof Selection.OfEntity sel)) {
            return;
        }
        Entity e = d.live().entity(sel.id());
        if (e == null || !e.present()) {
            return;
        }
        String room = d.live().roomOf(e);
        if (room == null || room.equals(followRoom)) {
            return;
        }
        followRoom = room;
        for (Entity c : d.live().ofKind(EntityKind.CAMERA)) {
            if (((CameraFacet) c.facet()).roomCode().equals(room)) {
                view(new Feed.Camera(c.name()));
                return;
            }
        }
        view(new Feed.Floorplan());
    }

    private WorldView liveWorld() {
        return liveWorld;
    }

    // ---- Operator ----------------------------------------------------------------------------

    @Override
    public void select(Selection s) {
        view.select(s);
        if (!(s instanceof Selection.None)) {
            side.show(SidePanel.Tab.INSPECT);
        }
        inspector.refresh(currentDisplay(), s);
        if (s instanceof Selection.OfAnomaly a) {
            session.discover(a.id());
        }
        // Looking is an act. The facility records what the operator inspects.
        if (!view.reviewing() && (s instanceof Selection.OfEntity || s instanceof Selection.OfAnomaly)) {
            session.runner().submit(new OperatorCommand.Inspect(s.ref()));
        }
    }

    @Override
    public void jumpTo(long tick, long seq) {
        Frame frame = session.runner().latest();
        long head = frame.status().tick();
        if (!view.reviewing()) {
            session.runner().setPaused(true);
        }
        view.playing().set(false);
        view.reviewTick().set(Math.clamp(tick, 0, head));
        if (seq >= 0) {
            view.select(new Selection.OfEvent(seq));
            side.show(SidePanel.Tab.INSPECT);
        }
    }

    @Override
    public void command(OperatorCommand command) {
        if (view.reviewing()) {
            overlays.toast("You are reviewing the recording. Return to LIVE to act on the facility.", "warn");
            return;
        }
        session.runner().submit(command).thenAccept(result -> Platform.runLater(() ->
                overlays.toast(result.message(), result.accepted() ? "ok" : "bad")));
    }

    @Override
    public void view(Feed feed) {
        if (feed.equals(view.feed().get())) {
            return;
        }
        view.feed().set(feed);
        sound.play(Cue.SWITCH, System.nanoTime());
        if (!view.reviewing()) {
            // Which room the operator is watching is part of the simulation.
            String code = feed instanceof Feed.Camera c ? c.code() : null;
            session.runner().submit(new OperatorCommand.SwitchCamera(code));
        }
    }

    @Override
    public void follow(EntityId id) {
        boolean on = !view.follow().get() || !(view.selection().get() instanceof Selection.OfEntity e && e.id().equals(id));
        view.follow().set(on);
        followRoom = null;
        view.select(new Selection.OfEntity(id));
        overlays.toast(on ? "following " + EventText.n(liveWorld, id) : "follow off", "ok");
    }

    @Override
    public void toggleTrace(EntityId id) {
        if (!view.traced().remove(id)) {
            view.traced().add(id);
            if (!(view.feed().get() instanceof Feed.Floorplan)) {
                overlays.toast("relationships are drawn on the floor plan (F)", "ok");
            }
        }
    }

    @Override
    public void writeNote(String targetRef) {
        side.show(SidePanel.Tab.NOTES);
        notes.refresh(currentDisplay(), view.selection().get());
        notes.focusDraft(targetRef);
    }

    @Override
    public boolean reviewing() {
        return view.reviewing();
    }

    private Display currentDisplay() {
        Frame frame = session.runner().latest();
        WorldView world = view.reviewing() && cursor != null ? cursor.seek(view.reviewTick().get()) : liveWorld;
        return new Display(world == null ? WorldState.restore(map, frame.snapshot()) : world,
                liveWorld == null ? WorldState.restore(map, frame.snapshot()) : liveWorld, frame, view.reviewing());
    }

    // ---- transport -----------------------------------------------------------------------------

    private void scrub(long delta) {
        long head = session.runner().latest().status().tick();
        long from = view.reviewing() ? view.reviewTick().get() : head;
        long target = from + delta;
        if (view.reviewing() && target >= head) {
            live();
            return;
        }
        jumpTo(Math.max(0, target), -1);
    }

    private void playPause() {
        if (view.reviewing()) {
            view.playing().set(!view.playing().get());
            reviewCarry = 0;
        } else {
            session.runner().setPaused(!session.runner().latest().status().paused());
        }
    }

    private void step() {
        if (view.reviewing()) {
            scrub(1);
        } else {
            session.runner().setPaused(true);
            session.runner().step(1);
        }
    }

    private void changeSpeed(boolean faster) {
        if (view.reviewing()) {
            Speed s = view.reviewSpeed().get();
            view.reviewSpeed().set(faster ? s.faster() : s.slower());
        } else {
            liveSpeed = faster ? liveSpeed.faster() : liveSpeed.slower();
            session.runner().setSpeed(liveSpeed);
        }
    }

    private void live() {
        boolean wasReviewing = view.reviewing();
        view.goLive();
        if (wasReviewing) {
            session.runner().setPaused(false);
            log.followLatest();
        }
    }

    private void resumeHere() {
        if (!view.reviewing()) {
            return;
        }
        long tick = view.reviewTick().get();
        long head = session.runner().latest().status().tick();
        dialogs.confirm("REWIND THE RECORDING?",
                "Everything recorded after " + FacilityClock.format(tick) + " (" + FacilityClock.duration(head - tick)
                        + ") will be discarded and the night will continue from there.\n\n"
                        + "The discarded recording cannot be recovered.",
                "REWIND", () -> session.runner().rewindTo(tick).thenAccept(ok -> Platform.runLater(() -> {
                    if (ok) {
                        view.goLive();
                        session.runner().setPaused(false);
                        overlays.banner("TIMELINE REWOUND", Palette.TEAL);
                    } else {
                        overlays.toast("rewind refused", "bad");
                    }
                })));
    }

    private void toggleSound() {
        boolean mute = !app.audio().muted();
        app.audio().setMuted(mute);
        header.setSound(!mute);
    }

    private void requestNew() {
        dialogs.newSession();
    }

    private void save() {
        dialogs.save();
    }

    private void load() {
        dialogs.load();
    }

    private void export() {
        dialogs.export();
    }

    // ---- keyboard ------------------------------------------------------------------------------

    /** @return true if the key was handled */
    public boolean onKey(KeyEvent e) {
        if (e.getTarget() instanceof TextInputControl) {
            if (e.getCode() == KeyCode.ESCAPE) {
                root.requestFocus();
                return true;
            }
            return false;
        }
        if (overlays.cardOpen()) {
            overlays.closeCard();
            return true;
        }
        if (e.isShortcutDown()) {
            switch (e.getCode()) {
                case S -> save();
                case O -> load();
                case E -> export();
                case N -> requestNew();
                default -> {
                    return false;
                }
            }
            return true;
        }
        switch (e.getCode()) {
            case SPACE -> playPause();
            case PERIOD -> step();
            case LEFT -> scrub(e.isShiftDown() ? -60 : -10);
            case RIGHT -> scrub(e.isShiftDown() ? 60 : 10);
            case OPEN_BRACKET -> changeSpeed(false);
            case CLOSE_BRACKET -> changeSpeed(true);
            case L -> live();
            case R -> resumeHere();
            case F -> view(new Feed.Floorplan());
            case S -> view(new Feed.SystemTable());
            case DIGIT0, DIGIT1, DIGIT2, DIGIT3, DIGIT4, DIGIT5, DIGIT6, DIGIT7, DIGIT8, DIGIT9 -> {
                String code = String.format("CAM-%02d", e.getCode().getCode() - KeyCode.DIGIT0.getCode());
                if (liveWorld != null && liveWorld.camera(code) != null) {
                    view(new Feed.Camera(code));
                }
            }
            case N -> writeNote(view.selection().get().ref());
            case T -> {
                if (view.selection().get() instanceof Selection.OfEntity s) {
                    toggleTrace(s.id());
                }
            }
            case W -> {
                if (view.selection().get() instanceof Selection.OfEntity s) {
                    follow(s.id());
                }
            }
            case M -> toggleSound();
            case F1, SLASH -> overlays.help();
            case ESCAPE -> {
                view.follow().set(false);
                select(new Selection.None());
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public void close() {
        timer.stop();
        session.close();
    }
}
