package ucai.kernel

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ucai.api.CourseDetail
import ucai.api.CreateRaceRequest
import ucai.api.RegisterTeamRequest
import ucai.api.RegisterTeamResponse

/** Who a team token belongs to: one team of one race. */
data class TeamPrincipal(val raceId: String, val teamId: String)

/** The admin bearer token was presented. */
data object AdminPrincipal

/**
 * The race registry (DESIGN §1 "kernel"): courses, races, team tokens. Tick loops run in [scope], on
 * [Dispatchers.Default], independent of request handling; [close] cancels them.
 */
class Kernel(val config: KernelConfig) {
    internal val log = config.log
    internal val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> config.log.error("kernel: $e") },
    )
    private val mutex = Mutex()
    private val races = LinkedHashMap<String, KernelRace>()
    private val tokens = HashMap<String, TeamPrincipal>()
    private var nextRace = 1
    private val courseDetails = HashMap<String, CourseDetail>()

    /** Public course detail (decimated points), computed once per course. */
    internal suspend fun courseDetail(id: String): CourseDetail? = mutex.withLock {
        courseDetails[id] ?: config.courses.get(id)?.decimatedDetail()?.also { courseDetails[id] = it }
    }

    suspend fun createRace(req: CreateRaceRequest): KernelRace {
        val course = config.courses.get(req.courseId) ?: badRequest("unknown course '${req.courseId}'")
        req.wind?.let { w ->
            if (!w.fromDeg.isFinite() || !w.speed.isFinite() || w.speed !in 0.0..30.0) badRequest("wind speed must be in 0..30 m/s")
        }
        val detail = courseDetail(course.id)!!
        return mutex.withLock {
            val id = "race-${nextRace++}"
            KernelRace(id, course, detail, req.seed, req.clock, req.wind, config, scope).also {
                races[id] = it
                log.info("race $id created on ${course.id}, seed ${req.seed}, ${req.clock.mode.name.lowercase()}")
            }
        }
    }

    suspend fun race(id: String): KernelRace? = mutex.withLock { races[id] }

    suspend fun races(): List<KernelRace> = mutex.withLock { races.values.toList() }

    suspend fun registerTeam(raceId: String, req: RegisterTeamRequest): RegisterTeamResponse {
        val race = race(raceId) ?: notFound("unknown race $raceId")
        val team = race.register(req)
        val token = mutex.withLock {
            var t: String
            do {
                t = config.tokenGenerator()
            } while (t in tokens || t.isBlank())
            tokens[t] = TeamPrincipal(raceId, team.info.id)
            t
        }
        log.info("race $raceId: team ${team.info.id} '${team.info.name}' registered")
        return RegisterTeamResponse(team.info.id, token, team.riders)
    }

    internal suspend fun principal(token: String): TeamPrincipal? = mutex.withLock { tokens[token] }

    internal fun isAdmin(token: String): Boolean = constantTimeEquals(token, config.adminToken)

    fun close() {
        scope.cancel()
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        var diff = a.length xor b.length
        for (i in 0 until maxOf(a.length, b.length)) {
            diff = diff or ((a.getOrElse(i) { ' ' }.code) xor (b.getOrElse(i) { ' ' }.code))
        }
        return diff == 0
    }
}
