package ucai.runner

import ucai.team.TeamBot
import kotlin.test.Test
import kotlin.test.assertEquals

class RunnerTest {
    @Test
    fun seesTeam() = assertEquals("http://x/api/v1", TeamBot("http://x").apiUrl)
}
