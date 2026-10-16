package ucai.kernel

import io.ktor.util.generateNonceBlocking
import io.ktor.util.logging.KtorSimpleLogger
import io.ktor.util.sha1
import ucai.api.CourseDetail
import ucai.api.CoursePoint
import ucai.engine.course.Course
import ucai.engine.riders.RiderGenerator
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Where the kernel gets its courses from. The JVM `main` loads the `races/courses` JSON files; tests and the runner can pass
 * courses directly ([CourseCatalog.of]).
 */
interface CourseCatalog {
    fun list(): List<Course>
    fun get(id: String): Course?

    companion object {
        fun of(courses: List<Course>): CourseCatalog = InMemoryCourseCatalog(courses)
    }
}

private class InMemoryCourseCatalog(courses: List<Course>) : CourseCatalog {
    private val byId = courses.associateBy { it.id }

    init {
        require(byId.size == courses.size) { "duplicate course ids" }
    }

    override fun list() = byId.values.sortedBy { it.id }
    override fun get(id: String) = byId[id]
}

/**
 * The public course detail (`GET /courses/{courseId}`, replay `course`): points decimated to one every [stepM]
 * (always keeping the finish), climbs unchanged.
 */
fun Course.decimatedDetail(stepM: Double = KernelConfig.COURSE_POINT_STEP_M): CourseDetail {
    val every = maxOf(1, (stepM / ucai.engine.course.COURSE_STEP_M).toInt())
    val kept = points.filterIndexed { i, _ -> i % every == 0 || i == points.lastIndex }
    return CourseDetail(
        id, name, distanceM, elevationGainM, startName, finishName,
        kept.map { CoursePoint(it.lat, it.lon, it.eleM, it.distM) }, climbs,
    )
}

/** Kernel log sink: the default forwards to Ktor's logger (slf4j/logback on the JVM). */
fun interface KernelLog {
    enum class Level { INFO, WARN, ERROR }

    fun log(level: Level, message: String)

    fun info(message: String) = log(Level.INFO, message)
    fun warn(message: String) = log(Level.WARN, message)
    fun error(message: String) = log(Level.ERROR, message)

    companion object {
        val DEFAULT: KernelLog = run {
            val logger = KtorSimpleLogger("ucai.kernel")
            KernelLog { level, message ->
                when (level) {
                    Level.INFO -> logger.info(message)
                    Level.WARN -> logger.warn(message)
                    Level.ERROR -> logger.error(message)
                }
            }
        }
    }
}

/**
 * Kernel settings.
 *
 * @property adminToken bearer token of the admin routes (`UCAI_ADMIN_TOKEN` in the JVM main).
 * @property maxSnapshotsPerSecond SSE `snapshot` rate cap, per wall second (DESIGN §4: ~10).
 * @property maxTeams teams per race (DESIGN §2: 10).
 * @property realtimeTick wall time of one tick in `realtime` races (1 s; tests shorten it).
 * @property heartbeat SSE comment heartbeat, also how fast a closed stream is noticed while idle.
 * @property streamBuffer events queued per SSE client; a client that falls this far behind is disconnected.
 * @property wallClock ISO-8601 wall time for `startedAt`; null leaves it out (common code never reads the clock).
 * @property tokenGenerator team tokens; the default joins 4 nonces of Ktor's secure nonce generator (64 hex chars).
 * @property seedSecret kernel-side secret mixed into every race seed ([raceSeed]) before riders are generated and the
 *   engine is seeded, so the public race seed alone never rebuilds the profiles (`UCAI_SEED_SECRET` in the JVM main;
 *   random by default). A fixed secret makes races reproducible (the batch runner).
 */
class KernelConfig(
    val adminToken: String,
    val courses: CourseCatalog,
    val maxSnapshotsPerSecond: Int = 10,
    val maxTeams: Int = RiderGenerator.TEAM_COUNT,
    val realtimeTick: Duration = 1.seconds,
    val heartbeat: Duration = 1.seconds,
    val streamBuffer: Int = 4096,
    val wallClock: (() -> String)? = null,
    val tokenGenerator: () -> String = { List(4) { generateNonceBlocking() }.joinToString("") },
    val log: KernelLog = KernelLog.DEFAULT,
    val seedSecret: String = List(2) { generateNonceBlocking() }.joinToString(""),
) {
    init {
        require(adminToken.isNotBlank()) { "the admin token must not be blank" }
        require(seedSecret.isNotEmpty()) { "the seed secret must not be empty" }
        require(maxSnapshotsPerSecond >= 1) { "maxSnapshotsPerSecond must be at least 1" }
        require(maxTeams in 1..99) { "maxTeams must be in 1..99" }
        require(streamBuffer >= 16) { "streamBuffer must be at least 16" }
    }

    /** Minimum wall time between two SSE snapshots. */
    val snapshotInterval: Duration get() = (1000.0 / maxSnapshotsPerSecond).milliseconds

    companion object {
        /** Spacing of the points of a public course detail. */
        const val COURSE_POINT_STEP_M = 50.0
    }
}

/** Environment variable of the kernel seed secret ([KernelConfig.seedSecret]). */
const val SEED_SECRET_ENV = "UCAI_SEED_SECRET"

/**
 * The seed a kernel race actually uses (rider generation and engine RNG): SHA-1 of [secret] and the public [seed], first
 * 8 bytes. Without the secret, the public seed and the public start list don't give back the profiles (DESIGN §4).
 */
fun raceSeed(seed: Long, secret: String): Long {
    val digest = sha1("$secret:$seed".encodeToByteArray())
    var z = 0L
    for (i in 0 until 8) z = (z shl 8) or (digest[i].toLong() and 0xFF)
    return z
}
