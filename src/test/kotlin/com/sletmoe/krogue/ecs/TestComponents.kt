package com.sletmoe.krogue.ecs

// Shared test components for the ecs suite. They are `internal` (not file-private)
// because they are used as reified type arguments to inline functions from inside
// lambdas — a file-private type can't escape its file through inlining, but a
// module-internal one resolves fine across the test source set.
internal data class Position(val x: Int, val y: Int) : Component

internal data class Health(val current: Int, val max: Int) : Component

internal data class Name(val value: String) : Component

internal data class Tagged(val tag: String) : Component

// An open component hierarchy used to verify that World.update writes back under
// the QUERIED type's key, not the returned value's runtime class.
internal open class Buff(val power: Int) : Component

internal class SuperBuff(power: Int) : Buff(power)
