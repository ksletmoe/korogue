package com.sletmoe.krogue.registry

/**
 * An immutable id → value lookup. The engine registers its built-ins and a consuming game
 * adds its own (via [GameModule]); systems resolve string ids (`strategyId`,
 * `calculatorId`, …) through the narrow `resolve` seam rather than depending on the whole
 * module (ADR-0009).
 */
class Registry<T> internal constructor(
    private val entries: Map<String, T>,
) {
    /** The value registered under [id], or throws if none — an unknown id is a programmer error. */
    fun resolve(id: String): T = entries[id] ?: error("No registry entry for id '$id'. Known: ${entries.keys}")

    operator fun contains(id: String): Boolean = id in entries

    val ids: Set<String> get() = entries.keys
}
