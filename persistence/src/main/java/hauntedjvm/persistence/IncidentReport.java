package hauntedjvm.persistence;

import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityKind;
import hauntedjvm.core.entity.EntityState;
import hauntedjvm.core.event.AnomalyEvent.AnomalyEscalated;
import hauntedjvm.core.incident.AnomalyRecord;
import hauntedjvm.core.incident.DetectedAnomaly;
import hauntedjvm.core.incident.EscalationLevel;
import hauntedjvm.core.state.WorldView;
import hauntedjvm.core.time.FacilityClock;
import hauntedjvm.core.timeline.LogDigest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A human-readable Markdown write-up of an investigation, for sharing outside the application.
 * Contains only simulation data and the operator's own notes.
 */
public final class IncidentReport {

    private IncidentReport() {
    }

    public static String render(Investigation inv, WorldView head) {
        StringBuilder md = new StringBuilder();
        md.append("# INCIDENT REPORT — ").append(inv.title()).append("\n\n");
        md.append("| | |\n|---|---|\n");
        md.append("| Facility | FACILITY-07 |\n");
        md.append("| Seed | `").append(inv.config().seedHex()).append("` |\n");
        md.append("| Night shift | ").append(inv.config().persons()).append(" staff |\n");
        md.append("| Recording | ").append(FacilityClock.format(0)).append(" – ")
                .append(FacilityClock.format(inv.headTick())).append(" (")
                .append(FacilityClock.duration(inv.headTick())).append(") |\n");
        md.append("| Final level | ").append(head.incident().escalation().label()).append(" |\n");
        md.append("| Timeline rewinds | ").append(head.incident().rewinds()).append(" |\n");
        md.append("| Events recorded | ").append(inv.eventCount()).append(" |\n");
        md.append("| Log digest | `").append(LogDigest.of(inv.timeline().log(), inv.eventCount())).append("` |\n\n");

        md.append("## Escalation\n\n");
        inv.timeline().log().forEach(0, inv.eventCount(), r -> {
            if (r.event() instanceof AnomalyEscalated e) {
                md.append("- `").append(FacilityClock.format(r.tick())).append("` ")
                        .append(EscalationLevel.of(e.from()).name()).append(" → ")
                        .append(EscalationLevel.of(e.to()).name())
                        .append(String.format(" (instability %.2f)", e.instability())).append('\n');
            }
        });
        md.append('\n');

        md.append("## Personnel\n\n| | Name | Role | State |\n|---|---|---|---|\n");
        for (Entity p : head.ofKind(EntityKind.PERSON)) {
            md.append("| ").append(p.id()).append(" | ").append(p.name()).append(" | ")
                    .append(p.mind().role()).append(" | ").append(p.state())
                    .append(p.state() == EntityState.MISSING ? " ⚠" : "").append(" |\n");
        }
        md.append('\n');

        List<AnomalyRecord> anomalies = head.incident().anomalies();
        md.append("## Anomalies (").append(anomalies.size()).append(")\n\n");
        for (AnomalyRecord r : anomalies) {
            DetectedAnomaly a = r.anomaly();
            md.append("- `").append(FacilityClock.format(a.tick())).append("` **").append(a.category())
                    .append('/').append(a.severity()).append("** ");
            md.append(r.status() == AnomalyRecord.Status.RETRACTED ? "~~record withdrawn~~" : a.summary());
            if (inv.discovered().contains(a.id())) {
                md.append(" _(examined)_");
            }
            md.append('\n');
        }
        md.append('\n');

        md.append("## Operator notes\n\n");
        if (inv.notes().isEmpty()) {
            md.append("_None._\n");
        }
        for (InvestigationNote n : inv.notes()) {
            md.append("> **").append(n.targetRef()).append("** at `").append(FacilityClock.format(n.tick()))
                    .append("`\n>\n> ").append(n.text().replace("\n", "\n> ")).append("\n\n");
        }
        return md.toString();
    }

    public static void write(Investigation inv, WorldView head, Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, render(inv, head), StandardCharsets.UTF_8);
    }
}
