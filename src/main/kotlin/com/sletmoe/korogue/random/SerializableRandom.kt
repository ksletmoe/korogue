package com.sletmoe.korogue.random

import kotlin.random.Random

/**
 * A deterministic [Random] backed by **xoshiro256\*\*** whose full state ([state]) is four
 * `Long`s — so a stream can be snapshotted and resumed *exactly*, which `kotlin.random.Random`
 * cannot (it doesn't expose its state). Being a [Random] subclass, it drops into every API
 * that already takes one (world-gen, `List.random(rng)`, `TickContext.random`, …).
 *
 * Construct via [fromSeed] / [fromState] (or [GameRandom] for named streams). xoshiro256**
 * is a well-known public-domain generator; see ADR-0009.
 */
class SerializableRandom private constructor(
    s0: Long,
    s1: Long,
    s2: Long,
    s3: Long,
) : Random() {
    private var a = s0
    private var b = s1
    private var c = s2
    private var d = s3

    /** One xoshiro256** step. */
    private fun nextRaw(): Long {
        val result = (b * 5).rotateLeft(7) * 9
        val t = b shl 17
        c = c xor a
        d = d xor b
        b = b xor c
        a = a xor d
        c = c xor t
        d = d.rotateLeft(45)
        return result
    }

    override fun nextBits(bitCount: Int): Int = if (bitCount == 0) 0 else (nextRaw() ushr (64 - bitCount)).toInt()

    /** The current generator state — feed back into [fromState] to resume exactly. */
    fun state(): RandomState = RandomState(a, b, c, d)

    companion object {
        fun fromState(state: RandomState): SerializableRandom {
            // xoshiro256** must not be all-zero; fall back to a fixed nonzero seed if so.
            val safe = if (state.s0 or state.s1 or state.s2 or state.s3 == 0L) SplitMix64.stateFrom(1L) else state
            return SerializableRandom(safe.s0, safe.s1, safe.s2, safe.s3)
        }

        fun fromSeed(seed: Long): SerializableRandom = fromState(SplitMix64.stateFrom(seed))
    }
}
