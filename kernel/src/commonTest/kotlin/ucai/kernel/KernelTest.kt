package ucai.kernel

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class KernelTest {
    @Test
    fun health() = testApplication {
        application { kernelModule() }
        assertEquals("ok", client.get("/api/v1/health").bodyAsText())
    }
}
