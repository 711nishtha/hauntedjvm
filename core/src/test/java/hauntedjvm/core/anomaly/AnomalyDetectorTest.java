package hauntedjvm.core.anomaly;

import static org.assertj.core.api.Assertions.assertThat;

import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityState;
import hauntedjvm.core.entity.MemoryKind;
import hauntedjvm.core.entity.MemoryTrace;
import hauntedjvm.core.event.CommunicationEvent.Channel;
import hauntedjvm.core.event.CommunicationEvent.MessageSent;
import hauntedjvm.core.event.EntityEvent.EntityObserved;
import hauntedjvm.core.event.EntityEvent.EntityStateChanged;
import hauntedjvm.core.event.EntityEvent.IdentityClaimed;
import hauntedjvm.core.event.EntityEvent.MemoryFormed;
import hauntedjvm.core.event.ProcessEvent.MemoryAllocated;
import hauntedjvm.core.event.ProcessEvent.ProcessStopped;
import hauntedjvm.core.incident.AnomalyCategory;
import hauntedjvm.core.incident.DetectedAnomaly;
import hauntedjvm.core.support.TestWorld;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnomalyDetectorTest {

    private static List<DetectedAnomaly> scanFrom(TestWorld w, long fromSeq) {
        return AnomalyDetector.scan(w.state(), w.log(), fromSeq, w.log().size());
    }

    @Test
    void aCleanGenesisHasNothingToReport() {
        TestWorld w = TestWorld.genesis(1234);
        assertThat(scanFrom(w, 0)).isEmpty();
    }

    @Test
    void recordsDatedBeforeTheyWereWrittenAreTemporal() {
        TestWorld w = TestWorld.genesis(1);
        w.advanceTo(100);
        long from = w.log().size();
        w.emitDated(new EntityObserved(w.person(0).id(), w.person(1).id(), "ROOM-02"), -500);
        assertThat(scanFrom(w, from)).extracting(DetectedAnomaly::category).containsExactly(AnomalyCategory.TEMPORAL);
    }

    @Test
    void aMemoryWithoutACauseIsFabricated() {
        TestWorld w = TestWorld.genesis(2);
        long from = w.log().size();
        w.emit(new MemoryFormed(w.person(0).id(), new MemoryTrace(0, 0, MemoryKind.SAW_ANOMALY, null, "ROOM-04",
                null, 0.9, -0.9, -1, "a door that was not there")));
        List<DetectedAnomaly> found = scanFrom(w, from);
        assertThat(found).extracting(DetectedAnomaly::category).containsExactly(AnomalyCategory.MEMORY);
        assertThat(found.getFirst().summary()).contains("No such event was recorded");
    }

    @Test
    void anObservationBackedMemoryIsFine() {
        TestWorld w = TestWorld.genesis(3);
        Entity a = w.person(0);
        Entity b = w.person(1);
        long from = w.log().size();
        long seen = w.emit(new EntityObserved(a.id(), b.id(), "ROOM-02")).seq();
        w.emit(new MemoryFormed(a.id(), new MemoryTrace(0, 0, MemoryKind.SAW_ENTITY, b.id(), "ROOM-02", null, 0.5,
                0.1, seen, "saw b")));
        assertThat(scanFrom(w, from)).isEmpty();
    }

    @Test
    void twoRecordsAnsweringToOneNameIsAnIdentityAnomaly() {
        TestWorld w = TestWorld.genesis(4);
        w.emit(new IdentityClaimed(w.person(0).id(), w.person(1).id()));
        assertThat(scanFrom(w, w.log().size())).anySatisfy(a -> {
            assertThat(a.category()).isEqualTo(AnomalyCategory.IDENTITY);
            assertThat(a.severity()).isEqualTo(4);
        });
    }

    @Test
    void aDeadProcessThatKeepsAllocatingIsSystemic() {
        TestWorld w = TestWorld.genesis(5);
        Entity p = w.state().process("archive-sync");
        w.advanceTo(10);
        w.emit(new ProcessStopped(p.id(), 0, "test"));
        w.advanceTo(20);
        long from = w.log().size();
        w.emit(new MemoryAllocated(p.id(), 4096, "unowned"));
        assertThat(scanFrom(w, from)).extracting(DetectedAnomaly::category).containsExactly(AnomalyCategory.SYSTEM);
    }

    @Test
    void messagesWithoutSendersAreCommunicationAnomalies() {
        TestWorld w = TestWorld.genesis(6);
        long from = w.log().size();
        w.emit(new MessageSent(null, null, Channel.INTERCOM, "ROOM-04 IS NOT EMPTY", null));
        assertThat(scanFrom(w, from)).extracting(DetectedAnomaly::category)
                .containsExactly(AnomalyCategory.COMMUNICATION);
    }

    @Test
    void oneMomentWithTwoOutcomesIsReported() {
        TestWorld w = TestWorld.genesis(7);
        Entity p = w.person(0);
        long from = w.log().size();
        w.emit(new EntityStateChanged(p.id(), EntityState.NORMAL, EntityState.AFRAID, "a"));
        w.emit(new EntityStateChanged(p.id(), EntityState.NORMAL, EntityState.SUSPICIOUS, "b"));
        assertThat(scanFrom(w, from)).anySatisfy(a -> assertThat(a.key()).startsWith("TEMPORAL:twice"));
    }

    @Test
    void missingPeopleAreReportedWithTheirLastBadgePosition() {
        TestWorld w = TestWorld.genesis(8);
        Entity p = w.person(0);
        w.emit(new EntityStateChanged(p.id(), p.state(), EntityState.MISSING, "test"));
        assertThat(scanFrom(w, w.log().size())).anySatisfy(a -> {
            assertThat(a.key()).isEqualTo("SPATIAL:missing:" + p.id());
            assertThat(a.summary()).contains(w.state().map().roomCodeAt(p.position()));
        });
    }
}
