plugins {
    id("hauntedjvm.java-conventions")
}

description = "Procedural audio synthesis on javax.sound.sampled. No bundled recordings."

dependencies {
    implementation(libs.slf4j.api)
}
