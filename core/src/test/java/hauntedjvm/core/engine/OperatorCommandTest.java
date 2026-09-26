package hauntedjvm.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import hauntedjvm.core.Simulations;
import hauntedjvm.core.config.SimulationConfig;
import hauntedjvm.core.entity.CameraFacet;
import hauntedjvm.core.entity.CameraStatus;
import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityKind;
import hauntedjvm.core.entity.EntityState;
import hauntedjvm.core.entity.LightMode;
import hauntedjvm.core.incident.Directive;
import org.junit.jupiter.api.Test;

class OperatorCommandTest {

    private SimulationEngine engine() {
        SimulationEngine e = Simulations.create(SimulationConfig.defaults(77));
        e.run(20);
        return e;
    }

    @Test
    void switchingCamerasIsRecordedAndChangesTheObservedRoom() {
        SimulationEngine e = engine();
        assertThat(e.execute(new OperatorCommand.SwitchCamera("CAM-03")).accepted()).isTrue();
        assertThat(e.world().incident().observedCamera()).isEqualTo("CAM-03");
        assertThat(e.world().observedRoom()).isEqualTo("ROOM-04");
        assertThat(e.execute(new OperatorCommand.SwitchCamera("CAM-99")).accepted()).isFalse();
    }

    @Test
    void killingTheCameraMultiplexerDegradesEveryFeed() {
        SimulationEngine e = engine();
        Entity camMux = e.world().process("cam-mux");
        assertThat(e.execute(new OperatorCommand.TerminateProcess(camMux.id())).accepted()).isTrue();
        e.step();
        assertThat(e.world().ofKind(EntityKind.CAMERA)).allSatisfy(c ->
                assertThat(((CameraFacet) c.facet()).status()).isEqualTo(CameraStatus.INTERFERENCE));
        assertThat(e.execute(new OperatorCommand.RestartProcess(camMux.id())).accepted()).isTrue();
        e.step();
        assertThat(e.world().ofKind(EntityKind.CAMERA)).allSatisfy(c ->
                assertThat(((CameraFacet) c.facet()).status()).isEqualTo(CameraStatus.ONLINE));
    }

    @Test
    void theWatchdogRestartsWhatTheOperatorKills() {
        SimulationEngine e = engine();
        Entity tape = e.world().process("tape-indexer");
        e.execute(new OperatorCommand.TerminateProcess(tape.id()));
        assertThat(e.world().entity(tape.id()).state()).isEqualTo(EntityState.TERMINATED);
        e.run(200);
        assertThat(e.world().entity(tape.id()).state()).isEqualTo(EntityState.RUNNING);
    }

    @Test
    void lightsAndLocksValidateTheirTargets() {
        SimulationEngine e = engine();
        assertThat(e.execute(new OperatorCommand.SetLight("ROOM-04", LightMode.OFF)).accepted()).isTrue();
        assertThat(e.world().light("ROOM-04")).isEqualTo(LightMode.OFF);
        assertThat(e.execute(new OperatorCommand.SetLight("ROOM-00", LightMode.ON)).accepted()).isFalse();
        assertThat(e.execute(new OperatorCommand.SetLight("ROOM-04", LightMode.EMERGENCY)).accepted()).isFalse();
        assertThat(e.execute(new OperatorCommand.SetDoorLock("DOOR-99", true)).accepted()).isFalse();
        String sealed = e.map().doors().stream().filter(d -> d.sealed()).findFirst().orElseThrow().code();
        assertThat(e.execute(new OperatorCommand.SetDoorLock(sealed, false)).accepted()).isFalse();
    }

    @Test
    void aGatherDirectiveDrawsCompliantStaffToTheRoom() {
        SimulationEngine e = engine();
        e.execute(new OperatorCommand.IssueDirective(Directive.Type.GATHER, "ROOM-02"));
        e.run(200);
        long inBreakRoom = e.world().occupants("ROOM-02").stream().filter(p -> p.kind() == EntityKind.PERSON).count();
        assertThat(inBreakRoom).isGreaterThanOrEqualTo(4);
    }
}
