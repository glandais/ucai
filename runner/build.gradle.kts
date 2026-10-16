plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(libs.versions.jvm.toolchain.get().toInt())
}

dependencies {
    implementation(projects.kernel)
    implementation(projects.team)
    implementation(libs.kotlinx.coroutines.core)
    runtimeOnly(libs.logback.classic)
    testImplementation(kotlin("test"))
}

application {
    mainClass = "ucai.runner.MainKt"
}

tasks.test {
    useJUnitPlatform()
    workingDir = rootDir
}

// Calibration harness (DESIGN §8): scripted team strategies against the engine, no HTTP.
// Paths (races/courses, replays/) are relative to the repository root.
tasks.register<JavaExec>("calibrate") {
    group = "application"
    description = "Runs the calibration harness on the monuments and writes replays/"
    mainClass = "ucai.runner.calibration.CalibrateKt"
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootDir
}
