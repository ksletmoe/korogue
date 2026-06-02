package com.sletmoe.krogue.components

import com.sletmoe.krogue.ecs.Component

/**
 * Which zone an entity currently belongs to, by id (not a Zone reference — keeps
 * components serializable and supports multi-zone worlds; resolve via World/zones).
 */
data class ZoneMember(val zoneId: String) : Component
