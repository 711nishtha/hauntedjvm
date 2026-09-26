plugins {
    id("hauntedjvm.java-conventions")
}

description = "Real JVM telemetry (MXBeans + JFR event streaming). Never mixed with simulated data."

dependencies {
    implementation(libs.slf4j.api)
}
