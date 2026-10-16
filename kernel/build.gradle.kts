plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(libs.versions.jvm.toolchain.get().toInt())
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(projects.engine)
            api(projects.api)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.sse)
            implementation(libs.ktor.server.auth)
            implementation(libs.ktor.server.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.server.test.host)
            implementation(libs.ktor.client.cio)
            implementation(libs.kotlinx.coroutines.test)
        }
        // JVM entry point only (main + CIO bootstrap, logging backend).
        jvmMain.dependencies {
            runtimeOnly(libs.logback.classic)
        }
    }
}

// Kernel server: `UCAI_ADMIN_TOKEN=... ./gradlew :kernel:run --args="--port 8080"`.
// Paths (races/courses) are relative to the repository root.
tasks.register<JavaExec>("run") {
    group = "application"
    description = "Runs the kernel server (CIO) on the courses of races/courses"
    mainClass = "ucai.kernel.MainKt"
    val main = kotlin.jvm().compilations.getByName("main")
    classpath = files(main.output.allOutputs, main.runtimeDependencyFiles)
    workingDir = rootDir
}
