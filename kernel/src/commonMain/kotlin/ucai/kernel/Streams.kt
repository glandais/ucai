package ucai.kernel

import io.ktor.http.HttpStatusCode
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.KSerializer
import ucai.api.ApiJson

/**
 * One open SSE connection: the public stream ([teamId] null) or a team stream. The race pushes events into a
 * bounded [channel] without suspending (the tick loop never waits for a client); a client that falls
 * [capacity] events behind is disconnected (it reconnects and starts with a fresh snapshot, DESIGN §4).
 */
internal class Subscriber(val teamId: String?, capacity: Int) {
    val channel = Channel<ServerSentEvent>(capacity)

    /** False once the subscriber was dropped for being too slow. */
    fun offer(event: ServerSentEvent): Boolean {
        if (channel.trySend(event).isSuccess) return true
        channel.close()
        return false
    }

    fun close() {
        channel.close()
    }
}

/** An SSE event with a JSON payload; `id` is the tick (DESIGN §4). */
internal fun <T> sse(event: String, serializer: KSerializer<T>, value: T, tick: Int?): ServerSentEvent =
    ServerSentEvent(data = ApiJson.encodeToString(serializer, value), event = event, id = tick?.toString())

/** A client error: the route answers [status] with an [ucai.kernel.ErrorResponse]. */
class ApiException(val status: HttpStatusCode, message: String) : Exception(message)

internal fun badRequest(message: String): Nothing = throw ApiException(HttpStatusCode.BadRequest, message)
internal fun notFound(message: String): Nothing = throw ApiException(HttpStatusCode.NotFound, message)
internal fun conflict(message: String): Nothing = throw ApiException(HttpStatusCode.Conflict, message)
