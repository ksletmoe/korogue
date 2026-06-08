package com.sletmoe.krogue.kotile

import com.sletmoe.krogue.utilities.Grid

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
internal class ZoneFog {
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
}
