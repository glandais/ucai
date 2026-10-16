package ucai.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Archetype {
    @SerialName("climber") CLIMBER,
    @SerialName("puncheur") PUNCHEUR,
    @SerialName("sprinter") SPRINTER,
    @SerialName("rouleur") ROULEUR,
    @SerialName("all_rounder") ALL_ROUNDER,
    @SerialName("domestique") DOMESTIQUE,
}

@Serializable
enum class RiderMode {
    @SerialName("follow") FOLLOW,
    @SerialName("pull") PULL,
    @SerialName("attack") ATTACK,
    @SerialName("sprint") SPRINT,
    @SerialName("recover") RECOVER,
}

@Serializable
enum class RiderStatus {
    @SerialName("racing") RACING,
    @SerialName("dropped") DROPPED,
    @SerialName("finished") FINISHED,
    @SerialName("abandoned") ABANDONED,
}

/** Coarse public ratings, each from 1 to 5. */
@Serializable
data class RiderRatings(
    val climbing: Int,
    val punch: Int,
    val sprint: Int,
    val endurance: Int,
    val descending: Int,
) {
    companion object {
        val RANGE = 1..5
    }
}

/** Exact physiology, only given to the rider's own team. */
@Serializable
data class RiderProfile(
    /** Rider + bike. */
    val massKg: Double,
    /** Critical power, W. */
    val cp: Double,
    /** Anaerobic reserve, J. */
    val wPrime: Double,
    /** Sprint ceiling, W. */
    val pMax: Double,
    /** Aero drag area alone at the front, m². */
    val cda: Double,
    /** 0..1: resistance of CP and W′ to accumulated work. */
    val durability: Double,
    /** 0..1: max safe speed on descents. */
    val descending: Double,
)

/** What everyone sees about a rider (TV view). */
@Serializable
data class PublicRider(
    val id: Int,
    val name: String,
    val teamId: String,
    val archetype: Archetype,
    val ratings: RiderRatings,
)

/** A rider as seen by its own team: public fields plus the exact [profile]. */
@Serializable
data class PrivateRider(
    val id: Int,
    val name: String,
    val teamId: String,
    val archetype: Archetype,
    val ratings: RiderRatings,
    val profile: RiderProfile,
) {
    fun toPublic() = PublicRider(id, name, teamId, archetype, ratings)
}

/** A rider supplied at team registration (otherwise riders are generated). Ratings are derived by the kernel. */
@Serializable
data class RiderSpec(
    val name: String,
    val archetype: Archetype,
    val profile: RiderProfile,
)

/** Full live state of one of the team's own riders. */
@Serializable
data class PrivateRiderState(
    val riderId: Int,
    val distM: Double,
    val speed: Double,
    val power: Double,
    val wPrimeBal: Double,
    val cpEff: Double,
    val wPrimeEff: Double,
    /** Work done since the start, kJ. */
    val energyKj: Double,
    val groupId: Int,
    val rankInGroup: Int,
    val mode: RiderMode,
    val status: RiderStatus,
)
