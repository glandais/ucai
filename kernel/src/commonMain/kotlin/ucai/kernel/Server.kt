package ucai.kernel

import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer

/** A running embedded kernel: [port] is the actual port (useful with port 0). */
class KernelServer internal constructor(
    val kernel: Kernel,
    val port: Int,
    private val server: EmbeddedServer<*, *>,
) {
    val baseUrl: String get() = "http://127.0.0.1:$port"

    fun stop(gracePeriodMillis: Long = 100, timeoutMillis: Long = 1_000) {
        server.stop(gracePeriodMillis, timeoutMillis)
        kernel.close()
    }
}

/**
 * Starts the kernel on CIO, embedded. Pass [port] 0 for a free port: the returned [KernelServer.port] is the one
 * bound (the runner starts the kernel in-process this way). [configure] runs on the application before the kernel
 * module (e.g. a plugin counting requests).
 */
suspend fun startKernelServer(
    config: KernelConfig,
    port: Int = 0,
    host: String = "127.0.0.1",
    configure: Application.() -> Unit = {},
): KernelServer {
    val kernel = Kernel(config)
    val server = embeddedServer(CIO, port = port, host = host) {
        configure()
        kernelModule(kernel)
    }
    server.startSuspend(wait = false)
    val actual = server.engine.resolvedConnectors().first().port
    config.log.info("kernel listening on $host:$actual")
    return KernelServer(kernel, actual, server)
}
