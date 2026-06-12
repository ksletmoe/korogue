package com.sletmoe.korogue.loop

/**
 * Decides **when** the ECS world advances a turn — the policy the per-frame game loop consults
 * (krogue-lhw). Two modes ship:
 * - [RealTimeLoop] — the world ticks continuously on its own (pair with throttled AI so creatures
 *   don't move at frame rate); this is the demo's original behavior.
 * - [TurnBasedLoop] — the world ticks once per committed player action and is otherwise frozen
 *   (classic roguelike turn-on-input; pair with AI that acts every turn).
 *
 * Distinct from `com.sletmoe.korogue.kotile.Game`, which is the libGDX app shell that drives frames;
 * a `GameLoop` only decides whether a frame should advance the *world*.
 */
interface GameLoop {
    /**
     * Records that the player has committed a turn-consuming action this frame. [TurnBasedLoop]
     * advances on it; [RealTimeLoop] ignores it (the world advances regardless of input).
     */
    fun requestTurn()

    /** Called once per frame; runs [tick] zero or more times according to this loop's policy. */
    fun advance(tick: () -> Unit)
}

/**
 * Advances the world **every frame** (continuous / real-time). The world runs on its own, so player
 * input doesn't gate it ([requestTurn] is a no-op). Pair with AI that only acts *sometimes* (a
 * per-tick act chance) so creatures don't move at the frame rate.
 */
class RealTimeLoop : GameLoop {
    override fun requestTurn() = Unit

    override fun advance(tick: () -> Unit) = tick()
}

/**
 * Advances the world **exactly once per committed player action** (turn-on-input); otherwise it
 * stays frozen. Multiple [requestTurn]s before an [advance] coalesce into a single turn (you can't
 * act twice before the world responds). Pair with AI that acts every turn, so one player action
 * resolves into one world turn for everyone.
 */
class TurnBasedLoop : GameLoop {
    private var pending = false

    override fun requestTurn() {
        pending = true
    }

    override fun advance(tick: () -> Unit) {
        if (!pending) return
        pending = false
        tick()
    }
}
