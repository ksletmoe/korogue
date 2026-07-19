package com.sletmoe.kotile.input

/**
 * Convenience base class for [KotileInputListener] implementations that only
 * need to handle a subset of input events.
 *
 * All methods are implemented as no-ops; override only the events you care
 * about.
 *
 * ```kotlin
 * processor.addListener(object : KotileInputAdapter() {
 *     override fun onTileClicked(tileX: Int, tileY: Int, button: Int) {
 *         println("Clicked tile ($tileX, $tileY)")
 *     }
 * })
 * ```
 */
open class KotileInputAdapter : KotileInputListener {
    override fun onKeyDown(keycode: Int) {}

    override fun onKeyUp(keycode: Int) {}

    override fun onKeyTyped(character: Char) {}

    override fun onTileClicked(
        tileX: Int,
        tileY: Int,
        button: Int,
    ) {}

    override fun onTileHovered(
        tileX: Int,
        tileY: Int,
    ) {}

    override fun onTileDragged(
        tileX: Int,
        tileY: Int,
        button: Int,
    ) {}

    override fun onScrolled(
        amountX: Float,
        amountY: Float,
    ) {}
}
