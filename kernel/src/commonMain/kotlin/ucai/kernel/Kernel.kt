package ucai.kernel

import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import ucai.api.ApiV1

/** Ktor module for the kernel; real routes come later. */
fun Application.kernelModule() {
    routing {
        get("${ApiV1.BASE_PATH}/health") { call.respondText("ok") }
    }
}
