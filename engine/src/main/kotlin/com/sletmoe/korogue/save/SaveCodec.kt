package com.sletmoe.korogue.save

import com.sletmoe.korogue.ecs.Entity
import com.sletmoe.korogue.ecs.EntityId
import com.sletmoe.korogue.ecs.World
import com.sletmoe.korogue.random.GameRandom
import com.sletmoe.korogue.registry.ComponentRegistry
import com.sletmoe.korogue.utilities.Grid
import com.sletmoe.korogue.world.GameWorld
import com.sletmoe.korogue.world.Zone
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

/**
 * A loaded game: the rebuilt [GameWorld], its [GameRandom], and per-zone fog-of-war memory
 * ([fog], zoneId → explored grid; empty if the save had none). The host restores [fog] into
 * its `ZoneFog` so explored areas survive a save/load (krogue-k77).
 */
data class LoadedGame(
    val world: GameWorld,
    val random: GameRandom,
    val fog: Map<String, Grid<Boolean>> = emptyMap(),
)

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

    fun save(
        world: GameWorld,
        random: GameRandom,
        fog: Map<String, Grid<Boolean>> = emptyMap(),
    ): ByteArray {
        val ecs = world.ecs
        val entities =
            ecs
                .entities()
                .map { entity -> SavedEntity(entity.id.value, entity.components.filter(components::isRegistered)) }
                .toList()
        val zones =
            world.zones.values.map {
                    zone ->
                SavedZone(zone.zoneId, zone.width, zone.height, flatten(zone.tiles))
            }
        val savedFog =
            fog.map { (zoneId, grid) -> SavedFog(zoneId, grid.width, grid.height, flatten(grid)) }
        val data =
            SaveData(
                currentZoneId = world.currentZoneId,
                turn = ecs.currentTurn,
                nextEntityId = ecs.nextEntityId,
                rng = random.snapshot(),
                zones = zones,
                entities = entities,
                fog = savedFog,
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
        val ecs = World()
        ecs.restore(
            turn = data.turn,
            nextId = data.nextEntityId,
            entities = data.entities.map { Entity(EntityId(it.id), it.components) },
        )
        return LoadedGame(GameWorld(ecs, zones, data.currentZoneId), GameRandom.restore(data.rng), fog)
    }

    private fun <T> flatten(grid: Grid<T>): List<T> {
        val out = ArrayList<T>(grid.width * grid.height)
        for (y in 0 until grid.height) {
            for (x in 0 until grid.width) out += grid[x, y]
        }
        return out
    }

    private fun rebuildZone(saved: SavedZone): Zone {
        val grid = Grid(saved.width, saved.height, saved.tiles.first())
        for (y in 0 until saved.height) {
            for (x in 0 until saved.width) grid[x, y] = saved.tiles[y * saved.width + x]
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

    companion object {
        const val FORMAT_VERSION = 1
    }
}
