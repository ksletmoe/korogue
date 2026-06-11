package com.sletmoe.korogue.random

/**
 * SplitMix64 — the standard way to expand a single 64-bit seed into well-distributed
 * values. Used to seed [SerializableRandom] (xoshiro256**'s authors recommend seeding it
 * from SplitMix64) and to derive named stream seeds in [GameRandom].
 */
object SplitMix64 {
    private val GAMMA = 0x9E3779B97F4A7C15uL.toLong() // golden-ratio increment
    private val C1 = 0xBF58476D1CE4E5B9uL.toLong()
    private val C2 = 0x94D049BB133111EBuL.toLong()

    /** The SplitMix64 finalizing mix of [z] (no increment) — also a good 64-bit hash step. */
    fun mix(z: Long): Long {
        var x = z
        x = (x xor (x ushr 30)) * C1
        x = (x xor (x ushr 27)) * C2
        return x xor (x ushr 31)
    }

    /** Expands [seed] into a 256-bit [RandomState] (four successive SplitMix64 outputs). */
    fun stateFrom(seed: Long): RandomState {
        var z = seed

        fun next(): Long {
            z += GAMMA
            return mix(z)
        }
        return RandomState(next(), next(), next(), next())
    }
}
