package ucai.kernel

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import ucai.api.ApiJson
import ucai.api.ApiPaths
import ucai.engine.course.Course
import java.io.File
import java.time.Instant
import kotlin.system.exitProcess

/** Loads every `*.json` course of [dir] (`races/courses`). */
fun loadCourses(dir: File): List<Course> {
    val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.sortedBy { it.name }.orEmpty()
    require(files.isNotEmpty()) { "no course file in ${dir.absolutePath}" }
    return files.map { ApiJson.decodeFromString(Course.serializer(), it.readText()) }
}

/**
 * Kernel server: `--port 8080 --host 0.0.0.0 --courses races/courses` (or `UCAI_PORT`, `UCAI_HOST`,
 * `UCAI_COURSES`), admin token from `UCAI_ADMIN_TOKEN` (required).
 */
fun main(args: Array<String>) {
    val opts = args.toList().chunked(2).associate { it[0].removePrefix("--") to it.getOrElse(1) { "" } }
    fun opt(name: String, env: String, default: String) = opts[name] ?: System.getenv(env) ?: default
    val token = System.getenv(ApiPaths.ADMIN_TOKEN_ENV)
    if (token.isNullOrBlank()) {
        System.err.println("${ApiPaths.ADMIN_TOKEN_ENV} must be set")
        exitProcess(2)
    }
    val port = opt("port", "UCAI_PORT", "8080").toInt()
    val host = opt("host", "UCAI_HOST", "0.0.0.0")
    val courses = loadCourses(File(opt("courses", "UCAI_COURSES", "races/courses")))
    val config = KernelConfig(
        adminToken = token,
        courses = CourseCatalog.of(courses),
        wallClock = { Instant.now().toString() },
    )
    runBlocking {
        startKernelServer(config, port, host)
        awaitCancellation()
    }
}
