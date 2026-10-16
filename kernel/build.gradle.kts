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
            implementation(libs.kotlinx.coroutines.test)
        }
        // JVM entry point only (main + CIO bootstrap, logging backend).
        jvmMain.dependencies {
            runtimeOnly(libs.logback.classic)
        }
    }
}
