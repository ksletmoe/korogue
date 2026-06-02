package com.sletmoe.krogue.components

import com.sletmoe.krogue.ecs.Component

/** A human-readable name and optional description for an entity. */
data class Named(val name: String, val description: String? = null) : Component
