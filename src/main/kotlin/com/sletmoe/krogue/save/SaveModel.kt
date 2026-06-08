package com.sletmoe.krogue.save

import com.sletmoe.krogue.ecs.Component
import com.sletmoe.krogue.random.GameRandomState
import com.sletmoe.krogue.world.Tile
import kotlinx.serialization.Polymorphic
import kotlinx.serialization.Serializable

/**
 * Outer save envelope. Decoded independently of [payload] so the version is always readable
 * — the seam that keeps a future migration framework possible (ADR-0009). [payload] is the
 * CBOR-encoded [SaveData] bytes, so it can later be routed through migrations before being
 * decoded into the current model.
 */
@Serializable
data class SaveEnvelope(
    val formatVersion: Int,
    val payload: ByteArray,
)

/**
 * The persisted game state (ADR-0009). Derived state (lightMap) and transient intents are
 * excluded. [fog] is player knowledge (explored cells), kept as its own per-zone section
 * rather than folded into [zones] (terrain) — it defaults to empty so older, fog-less
 * payloads still decode (the additive-change path from ADR-0009, no version bump).
 */
@Serializable
data class SaveData(
    val currentZoneId: String,
    val turn: Long,
    val nextEntityId: Long,
    val rng: GameRandomState,
    val zones: List<SavedZone>,
    val entities: List<SavedEntity>,
    val fog: List<SavedFog> = emptyList(),
)

/** A zone's terrain: dimensions plus its tiles in row-major order. */
@Serializable
data class SavedZone(
    val zoneId: String,
    val width: Int,
    val height: Int,
    val tiles: List<Tile>,
)

/** A zone's fog-of-war memory: which cells the player has ever seen, in row-major order. */
@Serializable
data class SavedFog(
    val zoneId: String,
    val width: Int,
    val height: Int,
    val seen: List<Boolean>,
)

/** One entity: its id and the persistable (registered) components, polymorphically encoded. */
@Serializable
data class SavedEntity(
    val id: Long,
    val components: List<@Polymorphic Component>,
)
