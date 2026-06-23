package com.sletmoe.korogue.ui

import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.utilities.IntRect
import com.sletmoe.korogue.utilities.Paginator

/**
 * A read-only, paginated list of text [rows] — a thin default rendering over a [Paginator] for
 * long listings that would otherwise overflow (and silently truncate past) a fixed box. The engine
 * makes no representation choices of its own: the footer text, colors and which keys turn the page
 * are all supplied by the caller, so a consumer (e.g. Rogue's inventory/discoveries overlays) can
 * reproduce its own look — `--Press space to continue--`, a `2/5` page counter, or nothing.
 *
 * When a [footer] is supplied, its bottom row is reserved for it (so a page holds `height - 1`
 * rows); the [footer] lambda is given the [Paginator] and may return null to leave that row blank.
 * With no [footer], the full box height is used. [advanceKeys] turn the page forward (invoking
 * [onDismiss] when already on the last page), [backKeys] step back. For a fully custom presentation,
 * skip this widget and drive a [Paginator] directly.
 */
class PagedTextList(
    override val bounds: IntRect,
    rows: List<String>,
    private val fg: Color = Color.WHITE,
    private val bg: Color = Color.BLACK,
    private val z: Int = 0,
    private val footer: ((Paginator<String>) -> String?)? = null,
    private val advanceKeys: Set<Int> = setOf(Input.Keys.SPACE, Input.Keys.PAGE_DOWN),
    private val backKeys: Set<Int> = setOf(Input.Keys.PAGE_UP),
    private val onDismiss: (() -> Unit)? = null,
) : Widget {
    private val footerRows: Int = if (footer != null) 1 else 0
    private val paginator: Paginator<String> = Paginator(rows, bounds.height - footerRows)

    /** The page model, exposed so callers can read [Paginator.page]/[Paginator.pageCount] (e.g. for a footer). */
    val pages: Paginator<String> get() = paginator

    override fun draw(surface: TileSurface) {
        paginator.currentPage.forEachIndexed { row, line ->
            surface.text(0, row, z, line.take(surface.width), fg, bg)
        }
        if (footer != null && surface.height > 0) {
            footer.invoke(paginator)?.let { surface.text(0, surface.height - 1, z, it.take(surface.width), fg, bg) }
        }
    }

    override fun handleKey(keycode: Int): Boolean =
        when (keycode) {
            in advanceKeys -> {
                if (!paginator.next()) onDismiss?.invoke()
                true
            }
            in backKeys -> {
                paginator.previous()
                true
            }
            else -> false
        }
}
