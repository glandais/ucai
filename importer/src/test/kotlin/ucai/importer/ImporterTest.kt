package ucai.importer

import ucai.engine.Engine
import kotlin.test.Test
import kotlin.test.assertEquals

class ImporterTest {
    @Test
    fun seesEngine() = assertEquals(1.0, Engine.TICK_S)
}
