package ucai.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class EngineTest {
    @Test
    fun tickIsOneSecond() = assertEquals(1.0, Engine.TICK_S)
}
