package com.sletmoe.krogue.components

import com.sletmoe.krogue.ecs.Component
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A human-readable name and optional description for an entity. */
@Serializable
@SerialName("named")
data class Named(val name: String, val description: String? = null) : Component
