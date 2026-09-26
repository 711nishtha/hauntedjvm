version = "0.9.0"

tasks.register("benchmark") {
    group = "verification"
    description = "Runs the JMH benchmark suite (simulation, replay, serialization)."
    dependsOn(":benchmark:jmh")
}
