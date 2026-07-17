package com.sletmoe.korogue.loop

/**
 * Decides **when** the ECS world advances a turn — the policy the per-frame game loop consults
 * (krogue-lhw). Two modes ship:
 * - [RealTimeLoop] — the world ticks continuously at a fixed timestep, decoupled from render FPS
 *   (krogue-k7q); this is the demo's original (real-time) behavior, made frame-rate independent.
 * - [TurnBasedLoop] — the world ticks once per committed player action and is otherwise frozen
 *   (classic roguelike turn-on-input; pair with AI that acts every turn).
 *
 * Distinct from `com.sletmoe.korogue.app.Game`, which is the libGDX app shell that drives frames;
 * a `GameLoop` only decides whether a frame should advance the *world*.
 */
interface GameLoop {
    /**
     * Records that the player has committed a turn-consuming action this frame. [TurnBasedLoop]
     * advances on it; [RealTimeLoop] ignores it (the world advances on the clock, not on input).
     */
    fun requestTurn()

    /**
     * Called once per frame with the wall-clock time [deltaMs] elapsed since the previous frame;
     * runs [tick] zero or more times according to this loop's policy. [TurnBasedLoop] ignores
     * [deltaMs] (it advances on input); [RealTimeLoop] accumulates it to advance at a fixed rate.
     */
    fun advance(
        deltaMs: Long,
        tick: () -> Unit,
    )
}

/**
 * Advances the world at a **fixed timestep** ([stepMs]) independent of render FPS (krogue-k7q):
 * per-frame deltas accumulate, and the world ticks once for each whole [stepMs] of accumulated time.
 * This decouples game speed from frame rate (a faster machine no longer means faster monsters) and
 * smooths over frame hitches.
 *
 * Player input doesn't gate it ([requestTurn] is a no-op) — the world runs on the clock. Pair with
 * AI that acts every tick, since each fixed tick is one world step.
 *
 * @param stepMs wall-clock milliseconds per world tick (e.g. 100 → 10 ticks/sec).
 * @param maxCatchUpSteps cap on ticks run in a single [advance] after a long frame. Without it, a
 *   one-second stall would queue dozens of ticks at once (the "spiral of death"); when the cap is
 *   hit, leftover accumulated time is dropped so the sim stays responsive rather than falling
 *   further behind.
 */
class RealTimeLoop(
    private val stepMs: Long = 100L,
    private val maxCatchUpSteps: Int = 5,
) : GameLoop {
    init {
        require(stepMs > 0) { "stepMs must be positive, was $stepMs" }
        require(maxCatchUpSteps > 0) { "maxCatchUpSteps must be positive, was $maxCatchUpSteps" }
    }

    private var accumulatorMs = 0L

    override fun requestTurn() = Unit

    override fun advance(
        deltaMs: Long,
        tick: () -> Unit,
    ) {
        accumulatorMs += deltaMs
        var steps = 0
        while (accumulatorMs >= stepMs && steps < maxCatchUpSteps) {
            tick()
            accumulatorMs -= stepMs
            steps++
        }
        // Hit the catch-up cap with time still owed: drop the backlog rather than tick forever.
        if (accumulatorMs >= stepMs) accumulatorMs = 0L
    }
}

/**
 * Advances the world **exactly once per committed player action** (turn-on-input); otherwise it
 * stays frozen. Multiple [requestTurn]s before an [advance] coalesce into a single turn (you can't
 * act twice before the world responds). Pair with AI that acts every turn, so one player action
 * resolves into one world turn for everyone. [advance]'s `deltaMs` is ignored — turns come from
 * input, not the clock.
 */
class TurnBasedLoop : GameLoop {
    private var pending = false

    override fun requestTurn() {
        pending = true
    }

    override fun advance(
        deltaMs: Long,
        tick: () -> Unit,
    ) {
        if (!pending) return
        pending = false
        tick()
    }
}
