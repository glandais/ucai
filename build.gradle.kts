import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Common modules must stay platform-neutral: dependencies go in commonMain only.
// jvmMain is tolerated for kernel (server main + CIO bootstrap), and must not pull JVM-only libs elsewhere.
val jvmMainAllowed = setOf("kernel")

subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
        afterEvaluate {
            val kmp = extensions.getByType<KotlinMultiplatformExtension>()
            val forbidden = buildList {
                if (project.name !in jvmMainAllowed) add("jvmMain")
                add("jvmTest")
            }
            kmp.sourceSets.matching { it.name in forbidden }.configureEach {
                val deps = listOf(
                    apiConfigurationName, implementationConfigurationName,
                    compileOnlyConfigurationName, runtimeOnlyConfigurationName,
                ).flatMap { configurations.getByName(it).dependencies }
                check(deps.isEmpty()) {
                    "${project.path}: '$name' declares dependencies ${deps.map { "${it.group}:${it.name}" }}; " +
                        "declare them in commonMain (common modules must not depend on JVM-only libs)"
                }
                val srcDirs = kotlin.srcDirs.filter { it.exists() && (it.listFiles()?.isNotEmpty() == true) }
                check(srcDirs.isEmpty()) {
                    "${project.path}: '$name' has sources $srcDirs; common modules keep code in commonMain"
                }
            }
        }
    }
}
