package ucai.kernel

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.httpVersion
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.SSEServerContent
import io.ktor.server.sse.heartbeat
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import ucai.api.ApiJson
import ucai.api.ApiPaths
import ucai.api.CourseSummary
import ucai.api.CreateRaceRequest
import ucai.api.CreateRaceResponse
import ucai.api.OrdersRequest
import ucai.api.RegisterTeamRequest

/** Error body of every 4xx answered by the kernel. */
@Serializable
data class ErrorResponse(val error: String)

private const val ADMIN = "admin"
private const val TEAM = "team"

/**
 * The kernel's Ktor module: API v1 (DESIGN §4) over [kernel].
 *
 * Security: admin routes need `Authorization: Bearer <UCAI_ADMIN_TOKEN>`; team routes need the team's token and
 * answer 403 when it belongs to another team or race; public routes are read-only and never expose exact physiology,
 * private live state, modes or the seed. A missing or unknown token is a 401.
 */
fun Application.kernelModule(kernel: Kernel) {
    install(ContentNegotiation) { json(ApiJson) }
    install(SSE)
    install(Authentication) {
        bearer(ADMIN) {
            authenticate { cred -> if (kernel.isAdmin(cred.token)) AdminPrincipal else null }
        }
        bearer(TEAM) {
            authenticate { cred -> kernel.principal(cred.token) }
        }
    }
    monitor.subscribe(ApplicationStopped) { kernel.close() }

    routing {
        route(ApiPaths.BASE) {
            get("/health") { call.respondText("ok") }
            publicRoutes(kernel)
            authenticate(ADMIN) { adminRoutes(kernel) }
            authenticate(TEAM) { teamRoutes(kernel) }
        }
    }
}

private fun Route.publicRoutes(kernel: Kernel) {
    get(ApiPaths.COURSES) {
        call.handle { call.respond(kernel.config.courses.list().map { it.summary() }.sortedBy(CourseSummary::id)) }
    }
    get(ApiPaths.COURSE) {
        call.handle {
            val id = call.parameters[ApiPaths.PARAM_COURSE_ID]!!
            call.respond(kernel.courseDetail(id) ?: notFound("unknown course '$id'"))
        }
    }
    get(ApiPaths.RACES) {
        call.handle { call.respond(kernel.races().map { it.summary() }) }
    }
    get(ApiPaths.RACE) {
        call.handle { call.respond(call.race(kernel).detail()) }
    }
    get(ApiPaths.RACE_STATE) {
        call.handle { call.respond(call.race(kernel).snapshot()) }
    }
    get(ApiPaths.RACE_RESULTS) {
        call.handle { call.respond(call.race(kernel).classification()) }
    }
    get(ApiPaths.RACE_REPLAY) {
        call.handle {
            val blob = call.race(kernel).replay()
            call.response.header(HttpHeaders.Vary, HttpHeaders.AcceptEncoding)
            val gzip = call.request.headers.getAll(HttpHeaders.AcceptEncoding).orEmpty()
                .flatMap { it.split(',') }.any { it.trim().substringBefore(';').equals("gzip", ignoreCase = true) }
            if (gzip) {
                call.response.header(HttpHeaders.ContentEncoding, "gzip")
                call.respondBytes(blob.gzip, ContentType.Application.Json)
            } else {
                call.respondBytes(blob.json, ContentType.Application.Json)
            }
        }
    }
    get(ApiPaths.RACE_STREAM) {
        call.handle {
            val race = call.race(kernel)
            call.stream(kernel, race, null)
        }
    }
}

private fun Route.adminRoutes(kernel: Kernel) {
    post(ApiPaths.RACES) {
        call.handle {
            val race = kernel.createRace(call.receive<CreateRaceRequest>())
            call.respond(HttpStatusCode.Created, CreateRaceResponse(race.id))
        }
    }
    post(ApiPaths.RACE_TEAMS) {
        call.handle {
            val raceId = call.parameters[ApiPaths.PARAM_RACE_ID]!!
            call.respond(HttpStatusCode.Created, kernel.registerTeam(raceId, call.receive<RegisterTeamRequest>()))
        }
    }
    post(ApiPaths.RACE_START) {
        call.handle {
            val wait = when (val w = call.request.queryParameters["wait"]) {
                null, "true" -> true
                "false" -> false
                else -> badRequest("wait must be true or false, got '$w'")
            }
            call.respond(call.race(kernel).start(wait))
        }
    }
}

private fun Route.teamRoutes(kernel: Kernel) {
    get(ApiPaths.TEAM) {
        call.handle {
            val (race, teamId) = call.team(kernel)
            call.respond(race.teamView(teamId))
        }
    }
    get(ApiPaths.TEAM_STREAM) {
        call.handle {
            val (race, teamId) = call.team(kernel)
            call.stream(kernel, race, teamId)
        }
    }
    post(ApiPaths.TEAM_ORDERS) {
        call.handle {
            val (race, teamId) = call.team(kernel)
            val (status, body) = race.postOrders(teamId, call.receive<OrdersRequest>())
            call.respond(status, body)
        }
    }
}

private suspend fun ApplicationCall.race(kernel: Kernel): KernelRace {
    val id = parameters[ApiPaths.PARAM_RACE_ID]!!
    return kernel.race(id) ?: notFound("unknown race $id")
}

/** The race and team of a team route, checked against the token's scope. */
private suspend fun ApplicationCall.team(kernel: Kernel): Pair<KernelRace, String> {
    val p = principal<TeamPrincipal>() ?: throw ApiException(HttpStatusCode.Unauthorized, "team token required")
    val raceId = parameters[ApiPaths.PARAM_RACE_ID]!!
    val teamId = parameters[ApiPaths.PARAM_TEAM_ID]!!
    if (p.raceId != raceId || p.teamId != teamId) throw ApiException(HttpStatusCode.Forbidden, "this token is for another team")
    val race = kernel.race(raceId) ?: notFound("unknown race $raceId")
    if (!race.hasTeam(teamId)) notFound("unknown team $teamId")
    return race to teamId
}

/**
 * An SSE stream (DESIGN §4): checks are done before the response starts, so errors are plain 4xx. Events come
 * from the race's [Subscriber] queue; a heartbeat comment notices closed connections while the race is idle.
 */
private suspend fun ApplicationCall.stream(kernel: Kernel, race: KernelRace, teamId: String?) {
    // Same headers as Ktor's `sse` route builder (which cannot answer 4xx before the stream starts).
    response.header(HttpHeaders.ContentType, ContentType.Text.EventStream.toString())
    response.header(HttpHeaders.CacheControl, "no-store")
    if (request.httpVersion.startsWith("HTTP/1.")) response.header(HttpHeaders.Connection, "keep-alive")
    response.header("X-Accel-Buffering", "no")
    respond(
        SSEServerContent(this) {
            val sub = race.subscribe(teamId)
            heartbeat { period = kernel.config.heartbeat }
            try {
                for (event in sub.channel) send(event)
            } finally {
                withContext(NonCancellable) { race.unsubscribe(sub) }
            }
            close()
        },
    )
}

private suspend fun ApplicationCall.handle(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: ApiException) {
        respond(e.status, ErrorResponse(e.message ?: e.status.description))
    } catch (e: BadRequestException) {
        respond(HttpStatusCode.BadRequest, ErrorResponse(e.cause?.message ?: e.message ?: "bad request"))
    } catch (e: ContentTransformationException) {
        respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "bad request"))
    } catch (e: SerializationException) {
        respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "bad request"))
    } catch (e: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "bad request"))
    }
}
