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
    // Admin HTTP client of the batch runner, and the request-counting plugin installed on the embedded kernel.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.core)
    runtimeOnly(libs.logback.classic)
    testImplementation(kotlin("test"))
}

application {
    mainClass = "ucai.runner.MainKt"
}

// Batch runner (DESIGN §6): paths (races/courses, replays/) are relative to the repository root.
tasks.named<JavaExec>("run") {
    workingDir = rootDir
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
