package com.sletmoe.korogue.random

import kotlin.random.Random

/**
 * The game's source of randomness: a master seed plus **named, isolated streams** derived
 * from it (ADR-0009). `stream("worldgen")` and `stream("combat")` never perturb each other,
 * so "same master seed ⇒ same world + loot" holds no matter how gameplay code evolves, and
 * a saved game resumes exactly via [snapshot]/[restore].
 *
 * Sharing the master seed reproduces a new game. Whether to expose seed entry is the
 * consuming game's choice; [random] gives a fresh random seed by default.
 */
class GameRandom private constructor(
    val masterSeed: Long,
    private val streams: MutableMap<String, SerializableRandom>,
) {
    /** The stream named [name], created (deterministically from the master seed) on first use. */
    fun stream(name: String): SerializableRandom =
        streams.getOrPut(name) { SerializableRandom.fromSeed(deriveStreamSeed(masterSeed, name)) }

    /** Captures the master seed and every live stream's state for save/load. */
    fun snapshot(): GameRandomState = GameRandomState(masterSeed, streams.mapValues { it.value.state() })

    companion object {
        /** A game reproducible from [masterSeed]. */
        fun fromSeed(masterSeed: Long): GameRandom = GameRandom(masterSeed, linkedMapOf())

        /** A game with a fresh random master seed (the default when no seed is supplied). */
        fun random(): GameRandom = fromSeed(Random.nextLong())

        /** Restores a [snapshot]: captured streams resume exactly; others re-derive on use. */
        fun restore(state: GameRandomState): GameRandom =
            GameRandom(
                state.masterSeed,
                state.streams.mapValuesTo(linkedMapOf()) { SerializableRandom.fromState(it.value) },
            )

        private fun deriveStreamSeed(
            masterSeed: Long,
            name: String,
        ): Long {
            var h = masterSeed
            for (ch in name) h = SplitMix64.mix(h xor ch.code.toLong())
            return SplitMix64.mix(h)
        }
    }
}
