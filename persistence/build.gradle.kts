plugins {
    id("hauntedjvm.java-conventions")
}

description = "Session archives: event log, snapshots, notes. Jackson lives here so the core stays annotation-free."

dependencies {
    api(project(":core"))
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)
}
