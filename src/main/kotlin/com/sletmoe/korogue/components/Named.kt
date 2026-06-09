package com.sletmoe.korogue.components

import com.sletmoe.korogue.ecs.Component
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A human-readable name and optional description for an entity. */
@Serializable
@SerialName("named")
data class Named(val name: String, val description: String? = null) : Component
