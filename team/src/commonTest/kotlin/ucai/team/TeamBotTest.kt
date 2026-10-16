package ucai.team

import kotlin.test.Test
import kotlin.test.assertEquals

class TeamBotTest {
    @Test
    fun apiUrl() = assertEquals("http://localhost:8080/api/v1", TeamBot("http://localhost:8080/").apiUrl)
}
