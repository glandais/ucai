plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(libs.versions.jvm.toolchain.get().toInt())
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(projects.api)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))

        }
    }
}
