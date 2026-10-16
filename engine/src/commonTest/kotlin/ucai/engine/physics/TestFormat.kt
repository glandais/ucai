package ucai.engine.physics

import kotlin.math.roundToLong

/** Common-code replacement for String.format in test logs. */
internal fun Double.r1(): String = ((this * 10).roundToLong() / 10.0).toString()
