package com.sletmoe.korogue.components

import com.sletmoe.korogue.ecs.Component
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Marker for the player-controlled entity (camera target, input recipient). */
@Serializable
@SerialName("player")
data object Player : Component
