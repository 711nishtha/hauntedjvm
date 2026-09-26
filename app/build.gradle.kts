plugins {
    id("hauntedjvm.java-conventions")
    application
    alias(libs.plugins.javafx)
}

description = "Desktop investigation station (JavaFX), CLI and headless runner."

javafx {
    version = libs.versions.javafx.get()
    modules("javafx.controls", "javafx.graphics")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":persistence"))
    implementation(project(":diagnostics"))
    implementation(project(":audio"))
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)
}

application {
    mainClass = "hauntedjvm.app.Main"
    applicationName = "hauntedjvm"
    // A modest fixed heap keeps the REAL JVM panel meaningful and GC behaviour stable.
    applicationDefaultJvmArgs = listOf("-Xms256m", "-Xmx1g", "-XX:+UseG1GC")
}
