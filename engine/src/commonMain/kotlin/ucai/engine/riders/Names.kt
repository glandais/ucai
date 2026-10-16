package ucai.engine.riders

import kotlin.random.Random

/**
 * Fictional names for teams and riders. Team names are invented; rider surnames are built from syllables
 * so the pool never reproduces a real professional on purpose.
 */
object Names {
    val TEAM_NAMES = listOf(
        "Aurora Velo", "Cobalt Wheelworks", "Falcon Ridge", "Marble Coast", "Nordwind Pro",
        "Orchid Velo", "Quartz Racing", "Saffron Dynamo", "Tidewater Cycling", "Umber Peaks",
        "Vireo Cycling", "Zephyrine", "Granite Hollow", "Lumen Pédale", "Ironleaf", "Halcyon Cyclo",
    )

    /** Distinct, readable team colors (CSS hex). */
    val TEAM_COLORS = listOf(
        "#d62728", "#1f77b4", "#2ca02c", "#ff7f0e", "#9467bd", "#8c564b", "#e377c2", "#17becf",
        "#bcbd22", "#393b79", "#7f7f7f", "#f0c419",
    )

    private val FIRST = listOf(
        "Adrien", "Bastian", "Cosimo", "Dorian", "Elio", "Fabrice", "Gaspard", "Henrik", "Ilario", "Jonas",
        "Kasper", "Lorenzo", "Mathis", "Niels", "Oskar", "Pieter", "Quentin", "Rafael", "Sören", "Tobias",
        "Ugo", "Valentin", "Wessel", "Xavier", "Yannick", "Zeno", "Anselm", "Brecht", "Ciro", "Dimitri",
        "Emilien", "Florian", "Gianni", "Hugo", "Ivo", "Jules", "Leandro", "Matteo", "Nolan", "Teodor",
    )

    private val PREFIX = listOf(
        "Bel", "Cor", "Dar", "Fen", "Gal", "Mor", "Ral", "Tor", "Var", "Zan",
        "Bra", "Cas", "Del", "Fal", "Lun", "Mar", "Pel", "Ros", "Sar", "Vel",
    )

    private val SUFFIX = listOf(
        "ardi", "enko", "eau", "ier", "ini", "ovic", "sen", "uet", "ard", "otti",
        "ens", "aux", "ek", "ström", "ano", "berg",
    )

    /** A rider name not yet in [used]; adds it to [used]. */
    fun riderName(random: Random, used: MutableSet<String>): String {
        repeat(1000) {
            val name = "${FIRST.random(random)} ${PREFIX.random(random)}${SUFFIX.random(random)}"
            if (used.add(name)) return name
        }
        // Practically unreachable (40 × 320 combinations): number the name to keep it unique.
        var k = 2
        val base = "${FIRST.random(random)} ${PREFIX.random(random)}${SUFFIX.random(random)}"
        while (!used.add("$base $k")) k++
        return "$base $k"
    }
}
