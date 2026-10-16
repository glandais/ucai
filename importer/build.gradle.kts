plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(libs.versions.jvm.toolchain.get().toInt())
}

dependencies {
    implementation(projects.engine)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}

application {
    mainClass = "ucai.importer.MainKt"
}

tasks.test {
    useJUnitPlatform()
}

// Paths (races/raw, races/courses) are relative to the repository root.
tasks.named<JavaExec>("run") {
    workingDir = rootDir
}
tasks.test {
    workingDir = rootDir
}
