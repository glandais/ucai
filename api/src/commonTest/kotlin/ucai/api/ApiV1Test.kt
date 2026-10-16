package ucai.api

import kotlin.test.Test
import kotlin.test.assertEquals

class ApiV1Test {
    @Test
    fun basePath() = assertEquals("/api/v1", ApiV1.BASE_PATH)
}
