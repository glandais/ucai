package ucai.kernel

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer

fun main() {
    embeddedServer(CIO, port = 8080, module = { kernelModule() }).start(wait = true)
}
