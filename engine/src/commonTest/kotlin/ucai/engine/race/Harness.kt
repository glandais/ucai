package ucai.engine.race

import ucai.api.Order
import ucai.api.RaceEvent

/** Runs [race] in lockstep, asking [script] for every team's orders at each decision point. */
fun runScripted(race: Race, script: (DecisionPoint, Race) -> Map<String, List<Order>> = { _, _ -> emptyMap() }) {
    var dp: DecisionPoint? = race.start()
    while (dp != null) {
        val orders = script(dp, race)
        if (orders.isNotEmpty()) {
            val res = race.applyOrders(dp.decisionId, orders)
            res.values.forEach { r -> check(r.rejected.isEmpty()) { "rejected: ${r.rejected}" } }
        }
        dp = race.runToNextDecision()
    }
}

/** Records every tick (rider views are live, so copy what you need). */
class TickLog : RaceObserver {
    class Frame(val tick: Int, val dist: Map<Int, Double>, val speed: Map<Int, Double>, val power: Map<Int, Double>,
                val group: Map<Int, Int>, val rank: Map<Int, Int>, val groups: Int)

    val frames = mutableListOf<Frame>()
    val events = mutableListOf<RaceEvent>()

    override fun onTick(race: RaceView, events: List<RaceEvent>) {
        this.events += events
        frames += Frame(
            race.tick,
            race.riders.associate { it.id to it.distM },
            race.riders.associate { it.id to it.speed },
            race.riders.associate { it.id to it.power },
            race.riders.associate { it.id to it.groupId },
            race.riders.associate { it.id to it.rankInGroup },
            race.groups.size,
        )
    }
}

inline fun <reified T : RaceEvent> Race.eventsOf(): List<T> = events.filterIsInstance<T>()

fun Race.rider(id: Int): RiderView = riders.first { it.id == id }
