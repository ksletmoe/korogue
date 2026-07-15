package com.sletmoe.korogue.save

import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.ecs.Entity
import com.sletmoe.korogue.ecs.EntityId
import com.sletmoe.korogue.ecs.World
import com.sletmoe.korogue.random.GameRandom
import com.sletmoe.korogue.registry.ComponentRegistry
import com.sletmoe.korogue.schedule.SchedulerState
import com.sletmoe.korogue.world.BLANK_TILE
import com.sletmoe.korogue.world.GameWorld
import com.sletmoe.korogue.world.Tile
import com.sletmoe.korogue.world.Zone
import com.sletmoe.kotile.utilities.Grid
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

/**
 * A loaded game: the rebuilt [GameWorld] and per-zone fog-of-war memory ([fog], zoneId →
 * explored grid; empty if the save had none). The host restores [fog] into its `ZoneFog` so
 * explored areas survive a save/load (krogue-k77).
 */
data class LoadedGame(
    val world: GameWorld,
    val fog: Map<String, Grid<Boolean>> = emptyMap(),
    val schedule: SchedulerState = SchedulerState(),
) {
    /**
     * The restored [GameRandom] — every stream resumed mid-sequence, so play continues as if
     * never saved. Owned by [world] (ADR-0025); surfaced here for convenience.
     */
    val random: GameRandom get() = world.random
}

/**
 * Saves/loads a game to/from CBOR bytes (ADR-0009). Built from a [ComponentRegistry] (a
 * `GameModule`'s `components`) so it knows how to (de)serialize every registered component;
 * unregistered components (transient intents) are skipped. The game owns *when* and *where*
 * to call these — they just translate between game state and a byte array.
 */
@OptIn(ExperimentalSerializationApi::class)
class SaveCodec(
    private val components: ComponentRegistry,
) {
    private val cbor = Cbor { serializersModule = components.serializersModule }

    /**
     * Encodes [world] — entities, terrain, and its [GameWorld.random] state — to bytes. The RNG
     * rides along with the world rather than being passed in, so a save cannot be written that
     * resumes on a different (or unseeded) stream.
     */
    fun save(
        world: GameWorld,
        fog: Map<String, Grid<Boolean>> = emptyMap(),
        schedule: SchedulerState = SchedulerState(),
    ): ByteArray {
        val ecs = world.ecs
        val entities =
            ecs
                .entities()
                .map { entity -> SavedEntity(entity.id.value, entity.components.filter(components::isRegistered)) }
                .toList()
        val zones = world.zones.values.map(::encodeZone)
        val savedFog =
            fog.map { (zoneId, grid) -> SavedFog(zoneId, grid.width, grid.height, flatten(grid)) }
        val data =
            SaveData(
                currentZoneId = world.currentZoneId,
                turn = ecs.currentTurn,
                nextEntityId = ecs.nextEntityId,
                rng = world.random.snapshot(),
                zones = zones,
                entities = entities,
                fog = savedFog,
                schedule = schedule,
            )
        val payload = cbor.encodeToByteArray(SaveData.serializer(), data)
        return cbor.encodeToByteArray(SaveEnvelope.serializer(), SaveEnvelope(FORMAT_VERSION, payload))
    }

    fun load(bytes: ByteArray): LoadedGame {
        val envelope = cbor.decodeFromByteArray(SaveEnvelope.serializer(), bytes)
        check(envelope.formatVersion == FORMAT_VERSION) {
            "Unsupported save format version ${envelope.formatVersion}; expected $FORMAT_VERSION"
        }
        val data = cbor.decodeFromByteArray(SaveData.serializer(), envelope.payload)

        val zones = data.zones.associate { it.zoneId to rebuildZone(it) }
        val fog = data.fog.associate { it.zoneId to rebuildFog(it) }
        val ecs = World(GameRandom.restore(data.rng))
        ecs.restore(
            turn = data.turn,
            nextId = data.nextEntityId,
            entities = data.entities.map { Entity(EntityId(it.id), it.components) },
        )
        return LoadedGame(GameWorld(ecs, zones, data.currentZoneId), fog, data.schedule)
    }

    private fun <T> flatten(grid: Grid<T>): List<T> {
        val out = ArrayList<T>(grid.width * grid.height)
        for (y in 0 until grid.height) {
            for (x in 0 until grid.width) out += grid[x, y]
        }
        return out
    }

    /**
     * Palette + RLE encodes a zone's terrain (krogue-yox): interns each distinct [Tile] into
     * [SavedZone.palette] (terrain is heavily repetitive — a demo zone is mostly one wall tile
     * and one floor tile) and run-length encodes the row-major sequence of palette indices, so
     * one CBOR entry covers a whole wall or corridor run instead of one per cell.
     */
    private fun encodeZone(zone: Zone): SavedZone {
        val palette = ArrayList<Tile>()
        val paletteIndex = HashMap<TileKey, Int>()
        val runs = ArrayList<TileRun>()
        var runIndex = -1
        var runCount = 0
        for (y in 0 until zone.height) {
            for (x in 0 until zone.width) {
                val tile = zone.tiles[x, y]
                val index =
                    paletteIndex.getOrPut(TileKey(tile)) {
                        palette += tile
                        palette.size - 1
                    }
                if (index == runIndex) {
                    runCount++
                } else {
                    if (runCount > 0) runs += TileRun(runIndex, runCount)
                    runIndex = index
                    runCount = 1
                }
            }
        }
        if (runCount > 0) runs += TileRun(runIndex, runCount)
        return SavedZone(zone.zoneId, zone.width, zone.height, palette, runs)
    }

    private fun rebuildZone(saved: SavedZone): Zone {
        // palette is empty only for a zero-cell zone, where the fill is never read; fall back
        // to BLANK_TILE rather than throwing on palette.first().
        val grid = Grid(saved.width, saved.height, saved.palette.firstOrNull() ?: BLANK_TILE)
        var i = 0
        for (run in saved.runs) {
            val tile = saved.palette[run.paletteIndex]
            repeat(run.count) {
                grid[i % saved.width, i / saved.width] = tile
                i++
            }
        }
        return Zone(saved.zoneId, grid)
    }

    private fun rebuildFog(saved: SavedFog): Grid<Boolean> {
        val grid = Grid(saved.width, saved.height, false)
        for (y in 0 until saved.height) {
            for (x in 0 until saved.width) grid[x, y] = saved.seen[y * saved.width + x]
        }
        return grid
    }

    /**
     * The palette-interning key for a [Tile]: its full field-value identity. [Tile] is an
     * `open class` (not `data class` — downstream games extend it), so it has no structural
     * `equals`/`hashCode`; this stands in for that, scoped to save/load. Colors are compared
     * by packed RGBA8888 int (how [GdxColorSerializer] already persists them), not GDX
     * [Color] identity.
     */
    private data class TileKey(
        val name: String,
        val glyph: Char,
        val color: Int,
        val backgroundColor: Int,
        val isWalkable: Boolean,
        val blocksLineOfSight: Boolean,
        val description: String?,
    ) {
        constructor(tile: Tile) : this(
            tile.name,
            tile.glyph,
            Color.rgba8888(tile.color),
            Color.rgba8888(tile.backgroundColor),
            tile.isWalkable,
            tile.blocksLineOfSight,
            tile.description,
        )
    }

    companion object {
        // v2 (krogue-yox): palette + RLE terrain encoding, replacing SavedZone.tiles (one full
        // Tile per cell). Not backward-compatible; old saves fail fast via the version check
        // above rather than silently misreading the new SavedZone shape — acceptable for a demo.
        const val FORMAT_VERSION = 2
    }
}
