import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.algorithms.color.NormalizedRgb
import com.sletmoe.korogue.algorithms.color.toNormalizedRgb
import com.sletmoe.korogue.algorithms.lighting.DiminishingLightValueCalculator
import com.sletmoe.korogue.algorithms.lighting.LightFlicker
import com.sletmoe.kotile.utilities.Vector2Int
import kotlin.math.sqrt

/**
 * The animation showcase's torchlight model, shared verbatim by both halves (extracted from
 * `AnimationShowcaseHarness` when that file was split, krogue-iid — no behavior change). It wraps
 * the engine's real presentation-side lighting/flicker primitives (krogue-ncl, reused as-is rather
 * than reinvented): a [DiminishingLightValueCalculator] for distance falloff and a [LightFlicker]
 * for the per-torch flame pulse. The sprite half tints its fully-opaque stone via [litTint]; the
 * glyph half multiplies its own base colors via [litColor]; both read the same [torchFrameIndex]
 * so a torch's art and its light stay in lockstep.
 */
internal class TorchLighting {
    private val lightCalculator = DiminishingLightValueCalculator()
    private val torchLightColor = NormalizedRgb(1.0, 0.92, 0.72)

    // krogue-ncl's real presentation-side flicker (engine/algorithms/lighting), reused as-is —
    // one shape shared by every torch, but each torch reads it at its own offset (via
    // torchSeed/torchFrameIndex below) so torches don't pulse in lockstep, mirroring MapPanel's
    // per-emitter phase. periodMs matches the torch sprite's full 2-frame loop (2x
    // ANIMATION_FRAME_MS) and the flicker carries no seed of its own, so this exact phase is also
    // what torchFrameIndex uses to pick the sprite/glyph frame — the light only brightens while
    // that same torch's own flame frame is the bright one.
    private val flicker = LightFlicker(amplitude = 0.05, periodMs = ANIMATION_FRAME_MS * 2)

    /** A distinct [LightFlicker] phase per torch, so nearby torches don't pulse in lockstep. */
    fun torchSeed(torch: Vector2Int): Long = torch.x * 137L + torch.y * 271L

    /**
     * Which of a torch's two flame states (0 = bright, 1 = dim — see [DawnLikeTorchTile] for
     * which sheet is which) is showing at [elapsedMs], reading the same [torchSeed]-offset clock
     * [lightIntensityAt] feeds into [flicker] — the single source of truth both the torch's own
     * art and its light read from, so a torch's glow only brightens while its own flame frame is
     * the bright one.
     */
    fun torchFrameIndex(
        torch: Vector2Int,
        elapsedMs: Long,
    ): Int = ((elapsedMs + torchSeed(torch)) / ANIMATION_FRAME_MS % 2).toInt()

    /**
     * Light intensity at cell ([x], [y]) at wall-clock [elapsedMs] from the brightest of [torches]
     * (wall-mounted or on a pillar — any position), via [DiminishingLightValueCalculator] — the
     * brightest torch wins rather than summing, matching `MapPanel`'s (engine `ui` package) own
     * nearest/brightest-source approximation (real per-source blending is the cost that
     * approximation exists to avoid).
     * Its own [flicker] phase (via [torchSeed]) then modulates that intensity, and [AMBIENT_MIN]
     * floors the result so cells outside every radius stay dimly visible, not pitch black.
     */
    fun lightIntensityAt(
        x: Int,
        y: Int,
        torches: List<Vector2Int>,
        elapsedMs: Long,
    ): Double {
        var brightest = 0.0
        var brightestTorch = torches.first()
        for (torch in torches) {
            val dx = (x - torch.x).toDouble()
            val dy = (y - torch.y).toDouble()
            val distance = sqrt(dx * dx + dy * dy)
            val intensity = lightCalculator.calculateLightValue(torchLightColor, LIGHT_RADIUS, distance).intensity
            if (intensity > brightest) {
                brightest = intensity
                brightestTorch = torch
            }
        }
        val flickerFactor = flicker.factorAt(elapsedMs + torchSeed(brightestTorch))
        return (brightest * flickerFactor).coerceIn(AMBIENT_MIN, 1.0)
    }

    /** [torchLightColor] scaled by the flickering light intensity at ([x], [y]), as a tint. */
    fun litTint(
        x: Int,
        y: Int,
        torches: List<Vector2Int>,
        elapsedMs: Long,
    ) = (torchLightColor * lightIntensityAt(x, y, torches, elapsedMs)).toColor()

    /** [base] multiplied by the flickering light at ([x], [y]) — the ASCII-side counterpart to [litTint]. */
    fun litColor(
        base: Color,
        x: Int,
        y: Int,
        torches: List<Vector2Int>,
        elapsedMs: Long,
    ) = (base.toNormalizedRgb() * torchLightColor * lightIntensityAt(x, y, torches, elapsedMs)).toColor()
}
