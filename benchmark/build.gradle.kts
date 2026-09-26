plugins {
    id("hauntedjvm.java-conventions")
    alias(libs.plugins.jmh)
}

description = "JMH benchmarks: tick throughput, entity scaling, event apply, reconstruction, serialization."

dependencies {
    jmh(project(":core"))
    jmh(project(":persistence"))
}

jmh {
    // Short defaults so `./gradlew benchmark` finishes in a few minutes on a laptop.
    // Override on the command line for publication-grade numbers.
    warmupIterations = 2
    warmup = "1s"
    iterations = 3
    timeOnIteration = "2s"
    fork = 1
    resultFormat = "JSON"
    profilers = listOf("gc")
}
