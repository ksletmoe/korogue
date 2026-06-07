package com.sletmoe.krogue.random

/**
 * The full, serializable state of a [SerializableRandom] — the four 64-bit words of a
 * xoshiro256** generator. Capturing this lets a stream be resumed exactly (the basis for
 * save/load, ADR-0009). Becomes `@Serializable` when the CBOR codec lands (4f step 3).
 */
data class RandomState(
    val s0: Long,
    val s1: Long,
    val s2: Long,
    val s3: Long,
)

/**
 * A snapshot of a [GameRandom]: the master seed plus every live stream's [RandomState].
 * Restoring it ([GameRandom.restore]) reproduces all streams exactly; streams not captured
 * here are re-derived deterministically from the master seed on first use.
 */
data class GameRandomState(
    val masterSeed: Long,
    val streams: Map<String, RandomState>,
)
