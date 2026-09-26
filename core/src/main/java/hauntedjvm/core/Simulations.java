package hauntedjvm.core;

import hauntedjvm.core.anomaly.AnomalySystem;
import hauntedjvm.core.anomaly.DetectionSystem;
import hauntedjvm.core.anomaly.EscalationSystem;
import hauntedjvm.core.behavior.BehaviorSystem;
import hauntedjvm.core.behavior.PerceptionSystem;
import hauntedjvm.core.config.SimulationConfig;
import hauntedjvm.core.engine.DeviceSystem;
import hauntedjvm.core.engine.ProcessSystem;
import hauntedjvm.core.engine.SimulationEngine;
import hauntedjvm.core.engine.SimulationSystem;
import hauntedjvm.core.memory.MemorySystem;
import hauntedjvm.core.memory.SocialSystem;
import hauntedjvm.core.timeline.Timeline;
import hauntedjvm.core.world.FacilityMap;
import java.util.List;

/**
 * Entry point for building engines with the standard FACILITY-07 pipeline.
 *
 * <p>The system order is part of the reproducibility contract: environment first (so people
 * react to the doors and lights of this tick), then perception, conversation and behaviour,
 * then memory upkeep, and finally the anomaly engine, which sees the tick's outcome before
 * deciding whether to interfere with the next one.
 */
public final class Simulations {

    private Simulations() {
    }

    public static List<SimulationSystem> standardSystems() {
        return List.of(
                new DeviceSystem(),
                new ProcessSystem(),
                new PerceptionSystem(),
                new SocialSystem(),
                new BehaviorSystem(),
                new MemorySystem(),
                new AnomalySystem(),
                new DetectionSystem(),
                new EscalationSystem());
    }

    public static SimulationEngine create(SimulationConfig config) {
        return SimulationEngine.create(config, FacilityMap.facility07(), standardSystems());
    }

    public static SimulationEngine resume(SimulationConfig config, Timeline timeline) {
        return SimulationEngine.resume(config, FacilityMap.facility07(), standardSystems(), timeline);
    }
}
