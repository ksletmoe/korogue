package com.sletmoe.krogue.components

import com.sletmoe.krogue.ecs.Component

/**
 * A zone-transition marker. When a (player) entity stands on this cell, it is sent to
 * ([targetX], [targetY]) in zone [targetZoneId]; resolved by `PortalSystem`. Portals are
 * non-blocking — you step *onto* them — so `MovementSystem` ignores them for occupancy.
 */
data class Portal(
    val targetZoneId: String,
    val targetX: Int,
    val targetY: Int,
) : Component
