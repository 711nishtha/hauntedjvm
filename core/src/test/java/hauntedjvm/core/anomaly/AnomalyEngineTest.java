package hauntedjvm.core.anomaly;

import static org.assertj.core.api.Assertions.assertThat;

import hauntedjvm.core.Simulations;
import hauntedjvm.core.config.SimulationConfig;
import hauntedjvm.core.engine.OperatorCommand;
import hauntedjvm.core.engine.SimulationEngine;
import hauntedjvm.core.entity.CameraFacet;
import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityKind;
import hauntedjvm.core.entity.EntityState;
import hauntedjvm.core.entity.LightMode;
import hauntedjvm.core.event.AnomalyEvent.AnomalyEscalated;
import hauntedjvm.core.event.AnomalyEvent.PerturbationApplied;
import hauntedjvm.core.world.RoomKind;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AnomalyEngineTest {

    private static Set<String> perturbationsIn(SimulationEngine e) {
        Set<String> names = new HashSet<>();
        e.timeline().log().forEach(0, Long.MAX_VALUE, r -> {
            if (r.event() instanceof PerturbationApplied p && p.scheduleId() < 0) {
                names.add(p.name());
            }
        });
        return names;
    }

    @Test
    void differentSeedsTellDifferentStories() {
        List<Set<String>> stories = new ArrayList<>();
        Set<String> all = new HashSet<>();
        for (long seed = 1; seed <= 6; seed++) {
            SimulationEngine e = Simulations.create(SimulationConfig.defaults(seed).withAnomalyIntensity(2.0));
            e.run(4_000);
            stories.add(perturbationsIn(e));
            all.addAll(stories.getLast());
        }
        assertThat(new HashSet<>(stories)).hasSizeGreaterThan(3);
        assertThat(all).hasSizeGreaterThanOrEqualTo(8);
    }

    @Test
    void nothingHappensDuringTheGracePeriod() {
        SimulationEngine e = Simulations.create(SimulationConfig.defaults(3).withAnomalyIntensity(10));
        e.run(AnomalySystem.GRACE_TICKS - 1);
        assertThat(perturbationsIn(e)).isEmpty();
    }

    @Test
    void zeroIntensityMeansAnOrdinaryNight() {
        SimulationEngine e = Simulations.create(SimulationConfig.defaults(3).withAnomalyIntensity(0).withRareEventRate(0));
        e.run(3_000);
        assertThat(perturbationsIn(e)).isEmpty();
        assertThat(e.world().incident().level()).isZero();
    }

    @Test
    void escalationClimbsOneLevelAtATime() {
        SimulationEngine e = Simulations.create(SimulationConfig.defaults(7).withAnomalyIntensity(3));
        e.run(6_000);
        e.timeline().log().forEach(0, Long.MAX_VALUE, r -> {
            if (r.event() instanceof AnomalyEscalated a) {
                assertThat(Math.abs(a.to() - a.from())).isEqualTo(1);
            }
        });
    }

    @Test
    void escalationHysteresis() {
        assertThat(Escalation.nextLevel(0, 1.3, 60)).isEqualTo(1);
        assertThat(Escalation.nextLevel(0, 1.3, 10)).isEqualTo(0);
        assertThat(Escalation.nextLevel(2, 4.6, 100)).isEqualTo(2);
        assertThat(Escalation.nextLevel(2, 4.6, 600)).isEqualTo(3);
        assertThat(Escalation.nextLevel(3, 4.0, 1_000)).isEqualTo(3);
        assertThat(Escalation.nextLevel(3, 1.0, 1_000)).isEqualTo(2);
        assertThat(Escalation.nextLevel(5, 100, 100_000)).isEqualTo(5);
    }

    /**
     * Operator counterplay: hold the unaccounted figure on a lit camera and it retreats behind
     * the sealed wall.
     */
    @Test
    void holdingTheFigureInTheLightBanishesIt() {
        SimulationEngine e = Simulations.create(SimulationConfig.defaults(7).withAnomalyIntensity(3));
        Entity unknown = null;
        for (int i = 0; i < 20_000 && unknown == null; i++) {
            e.step();
            unknown = e.world().ofKind(EntityKind.UNKNOWN).stream()
                    .filter(u -> u.present() && u.state() != EntityState.DORMANT && camera(e, e.world().roomOf(u)) != null)
                    .findFirst().orElse(null);
        }
        assertThat(unknown).as("an unaccounted figure on camera").isNotNull();
        String room = e.world().roomOf(unknown);
        e.execute(new OperatorCommand.SwitchCamera(camera(e, room)));
        e.execute(new OperatorCommand.SetLight(room, LightMode.ON));
        e.run(60);
        Entity after = e.world().entity(unknown.id());
        assertThat(e.world().incident().level()).as("exposure only works below level 5").isLessThan(5);
        assertThat(after.state()).isEqualTo(EntityState.DORMANT);
        assertThat(e.map().roomAt(after.position()).kind()).isEqualTo(RoomKind.HIDDEN);
    }

    private static String camera(SimulationEngine e, String room) {
        return e.world().ofKind(EntityKind.CAMERA).stream()
                .map(c -> (CameraFacet) c.facet())
                .filter(c -> c.roomCode().equals(room))
                .map(CameraFacet::code)
                .findFirst().orElse(null);
    }
}
