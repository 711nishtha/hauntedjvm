// Shared build logic for every HAUNTEDJVM module: toolchain, strict compilation,
// Checkstyle, JUnit Platform (Jupiter + jqwik) and coverage reports.
plugins {
    `java-library`
    checkstyle
    jacoco
}

val libs = the<VersionCatalogsExtension>().named("libs")

group = "io.github.711nishtha"
version = rootProject.version

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
    // Warnings are errors: a simulation that must stay deterministic cannot afford
    // unchecked casts or fall-through switches slipping in unnoticed.
    options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-processing", "-Werror"))
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

checkstyle {
    toolVersion = libs.findVersion("checkstyle").get().requiredVersion
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    maxWarnings = 0
}

dependencies {
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testImplementation(libs.findLibrary("assertj").get())
    testImplementation(libs.findLibrary("jqwik").get())
    testRuntimeOnly(libs.findLibrary("junit-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform {
        includeEngines("junit-jupiter", "jqwik")
    }
    // jqwik keeps a failure database; keep it out of the source tree.
    systemProperty("jqwik.database", layout.buildDirectory.file("jqwik-database").get().asFile.path)
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    finalizedBy(tasks.named("jacocoTestReport"))
}

tasks.named<JacocoReport>("jacocoTestReport") {
    reports {
        xml.required = true
        html.required = true
    }
}
