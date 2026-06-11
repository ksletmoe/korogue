package com.sletmoe.korogue.components

import com.sletmoe.korogue.ecs.Component
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Marks an entity as a collectible item lying in the world (with [Position] + [Renderable]).
 * Stepping onto it picks it up: `PickupSystem` despawns the entity and adds [name] to the player's
 * [Inventory]. Minimal for now (just a display name); richer item data will grow with the Rogue
 * example (krogue-sdh).
 */
@Serializable
@SerialName("item")
data class Item(
    val name: String,
) : Component
