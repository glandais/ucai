package ucai.api

import kotlinx.serialization.json.Json

/**
 * The JSON configuration shared by kernel, bots and tools.
 *
 * - nulls are omitted (`explicitNulls = false`), defaults are written so non-Kotlin clients see them;
 * - unknown keys are ignored, so adding fields is backward compatible;
 * - polymorphic types ([RaceEvent]) use a `type` field with snake_case names.
 *
 * Always encode events as [RaceEvent] (not as a concrete subclass) so `type` is written.
 */
val ApiJson: Json = Json {
    explicitNulls = false
    encodeDefaults = true
    ignoreUnknownKeys = true
    classDiscriminator = "type"
}
