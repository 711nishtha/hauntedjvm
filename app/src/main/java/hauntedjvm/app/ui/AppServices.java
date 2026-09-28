package hauntedjvm.app.ui;

import hauntedjvm.app.session.Session;
import hauntedjvm.audio.AudioEngine;
import hauntedjvm.diagnostics.TelemetryService;
import hauntedjvm.persistence.SessionArchive;
import hauntedjvm.persistence.SessionLibrary;
import java.util.concurrent.ExecutorService;
import javafx.stage.Stage;

/** Application-wide services a workstation borrows; they outlive any single session. */
interface AppServices {

    TelemetryService telemetry();

    AudioEngine audio();

    Stage stage();

    boolean debug();

    /** Virtual-thread executor for file I/O, so saving never stalls rendering. */
    ExecutorService io();

    SessionLibrary library();

    SessionArchive archive();

    void newSession(long seed, int persons);

    void open(Session session, String message);
}
