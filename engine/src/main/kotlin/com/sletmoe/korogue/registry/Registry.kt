package com.sletmoe.korogue.registry

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

    companion object {
        /**
         * A standalone registry over [entries], built without a [GameModule] (krogue-cjv). The
         * built-in systems take `Registry<T>` rather than a bare resolver lambda, so a consumer
         * testing one in isolation needs a way to make a small stub registry — standing up a whole
         * module for two entries would be the wrong tax, and the primary constructor is `internal`.
         */
        fun <T> of(entries: Map<String, T>): Registry<T> = Registry(entries.toMap())

        /** A standalone registry over [entries], as `Registry.of("wander" to strategy)`. */
        fun <T> of(vararg entries: Pair<String, T>): Registry<T> = Registry(entries.toMap())
    }
}
