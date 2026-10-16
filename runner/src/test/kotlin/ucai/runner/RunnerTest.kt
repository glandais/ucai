package ucai.runner

import kotlin.test.Test
import kotlin.test.assertEquals

class RunnerTest {
    @Test
    fun seesTeam() = assertEquals("http://x/api/v1", ucai.team.TeamBotConfig("http://x", "r", "t", "tok").apiUrl)
}
