package com.sletmoe.korogue.perception

import com.sletmoe.kotile.utilities.Grid

/**
 * Fog-of-war memory kept per zone, owned by the host (not the renderer). The renderer is
 * rebuilt on every zone change, so its "previously viewed" grid would reset on each
 * transition; holding the grids here and handing the right one back keeps explored areas
 * remembered when the player returns to a zone (krogue-ro8).
 *
 * Each grid is the mutable accumulator the renderer marks currently-visible cells into;
 * [forZone] returns the *same* grid for a given zone id across calls, so exploration is
 * additive across visits.
 */
class ZoneFog {
    private val byZone = mutableMapOf<String, Grid<Boolean>>()

    /**
     * The fog grid for [zoneId], created (fully unexplored, [width]×[height]) on first use
     * and reused on every later call for that zone.
     */
    fun forZone(
        zoneId: String,
        width: Int,
        height: Int,
    ): Grid<Boolean> = byZone.getOrPut(zoneId) { Grid(width, height, false) }

    /**
     * Discards the remembered fog for [zoneId] — for an ephemeral zone the player has left for good
     * (a regenerated roguelike level, say), so it neither lingers in memory nor rides into a save.
     * A no-op if the zone was never explored; a later [forZone] call starts it fresh.
     */
    fun forget(zoneId: String) {
        byZone.remove(zoneId)
    }

    /**
     * A defensive copy of every zone's fog grid, for persistence — pass to `SaveCodec.save`.
     * Copies decouple the snapshot from the live grids, which keep mutating after a save.
     */
    fun snapshot(): Map<String, Grid<Boolean>> = byZone.mapValues { it.value.copy() }

    /** Replaces all fog memory with copies of [grids] (e.g. from `LoadedGame.fog` on load). */
    fun restore(grids: Map<String, Grid<Boolean>>) {
        byZone.clear()
        grids.forEach { (zoneId, grid) -> byZone[zoneId] = grid.copy() }
    }
}
