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
}
