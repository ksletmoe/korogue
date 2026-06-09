package com.sletmoe.korogue.components

import com.sletmoe.korogue.ecs.Component
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The items an entity is carrying, as display names (immutable — add by replacing via
 * `World.update`). Held by the player; shown by the inventory panel. A list of names is the
 * minimal model for now; carried items will become richer (entities/components) with the Rogue
 * example (krogue-sdh).
 */
@Serializable
@SerialName("inventory")
data class Inventory(
    val items: List<String> = emptyList(),
) : Component
