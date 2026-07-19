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
    override fun onKeyDown(keycode: Int) = Unit

    override fun onKeyUp(keycode: Int) = Unit

    override fun onKeyTyped(character: Char) = Unit

    override fun onTileClicked(
        tileX: Int,
        tileY: Int,
        button: Int,
    ) = Unit

    override fun onTileHovered(
        tileX: Int,
        tileY: Int,
    ) = Unit

    override fun onTileDragged(
        tileX: Int,
        tileY: Int,
        button: Int,
    ) = Unit

    override fun onScrolled(
        amountX: Float,
        amountY: Float,
    ) = Unit
}
