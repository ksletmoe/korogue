package com.sletmoe.kotile.tiles

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.Texture.TextureFilter
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.utils.Disposable

/**
 * Slices an image into a grid of equally sized tiles, uploaded to the GPU.
 *
 * ## Filtering
 *
 * The **magnification** filter is always nearest-neighbour, so pixel art stays
 * crisp when the on-screen tile is drawn at or above its native size (1x and
 * every integer/fractional upscale are unchanged).
 *
 * The **minification** filter depends on [useMipMaps]. When `true` (the
 * default) a full mipmap chain is generated and the texture is sampled with a
 * trilinear (mip + linear) min-filter, so drawing a tile *smaller* than its
 * native size — a big sheet in a small window, or a `FitScale`/reflow factor
 * below 1x — averages the source pixels instead of dropping them, removing the
 * aliasing shimmer nearest-neighbour minification produces. When `false` the
 * min-filter is nearest-neighbour (the original behaviour) and no mipmaps are
 * allocated. Mipmapping only affects the downscale direction; upscaling is
 * governed by the nearest mag-filter either way.
 *
 * Mipmaps are safe here because the sheet is uploaded as a power-of-two texture
 * (see below) with `ClampToEdge` wrapping; GL requires POT for a complete mip
 * chain. Adjacent glyphs/tiles can bleed into one another at very coarse mip
 * levels (extreme downscales) if the tile pitch does not divide evenly; keep
 * [spacing] non-zero for sheets that will be shrunk aggressively.
 *
 * Owns the backing texture and must be [dispose]d.
 *
 * @param file the image to load (e.g. `Gdx.files.classpath("sheet.png")`)
 * @param keyColor if non-null, every pixel of exactly this color is made fully
 *   transparent on load, so a sheet authored with a solid background (e.g.
 *   magenta or black) can be alpha-blended. Pass `null` to keep the image as-is.
 * @param margin empty border, in pixels, around the whole sheet (Tiled-style)
 * @param spacing gap, in pixels, between adjacent tiles (Tiled-style)
 * @param useMipMaps generate a mipmap chain and use a trilinear min-filter for
 *   clean downscaling. Defaults to `true`. Pass `false` to keep the legacy
 *   nearest-neighbour minification and skip the ~33% mipmap VRAM cost.
 * @property tileWidthPx width of a single tile, in pixels
 * @property tileHeightPx height of a single tile, in pixels
 */
class TileSheet(
    file: FileHandle,
    val tileWidthPx: Int,
    val tileHeightPx: Int,
    keyColor: Color? = null,
    private val margin: Int = 0,
    private val spacing: Int = 0,
    useMipMaps: Boolean = true,
) : Disposable {
    private val texture: Texture

    /** Number of whole tiles across the sheet. */
    val widthInTiles: Int

    /** Number of whole tiles down the sheet. */
    val heightInTiles: Int

    init {
        val source = decodeForKeying(Pixmap(file), keyColor)
        if (keyColor != null) {
            zeroOutColor(source, keyColor)
        }

        // Tile counts are derived from the *source* image dimensions, before any
        // power-of-two padding below.
        widthInTiles = tilesAlong(source.width, tileWidthPx)
        heightInTiles = tilesAlong(source.height, tileHeightPx)

        // Upload as a power-of-two texture. Some OpenGL drivers — notably
        // Apple's on macOS — mishandle sampling *sub-regions* of a non-power-of-
        // two texture, which renders glyphs sliced from an NPOT atlas as garbage
        // (shrunk/whole-atlas speckles) even though full-texture draws are fine.
        // Padding the sheet up to the next power of two on each axis sidesteps
        // this. Glyph pixel coordinates are unchanged (the source is copied into
        // the top-left), so region slicing and the resulting UVs stay correct;
        // the added border is transparent and never sampled by a valid region.
        val potWidth = nextPowerOfTwo(source.width)
        val potHeight = nextPowerOfTwo(source.height)
        val upload =
            if (potWidth == source.width && potHeight == source.height) {
                source
            } else {
                Pixmap(potWidth, potHeight, Pixmap.Format.RGBA8888).apply {
                    blending = Pixmap.Blending.None
                    drawPixmap(source, 0, 0)
                }
            }

        texture = Texture(upload, useMipMaps)
        // Mag stays nearest (crisp upscaling / 1x). Min uses the mip chain when
        // requested so downscaling averages instead of dropping pixels.
        val minFilter = if (useMipMaps) TextureFilter.MipMapLinearLinear else TextureFilter.Nearest
        texture.setFilter(minFilter, TextureFilter.Nearest)
        texture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge)

        if (upload !== source) upload.dispose()
        source.dispose()
    }

    private fun tilesAlong(
        imagePx: Int,
        tilePx: Int,
    ): Int {
        val usable = imagePx - 2 * margin + spacing
        return if (usable > 0) usable / (tilePx + spacing) else 0
    }

    /**
     * Returns the region for the tile at column [x], row [y].
     *
     * @throws IllegalArgumentException if [x] or [y] is outside the sheet
     */
    fun region(
        x: Int,
        y: Int,
    ): TextureRegion {
        require(x in 0 until widthInTiles) { "x must be in 0 until $widthInTiles, was $x" }
        require(y in 0 until heightInTiles) { "y must be in 0 until $heightInTiles, was $y" }

        val px = margin + x * (tileWidthPx + spacing)
        val py = margin + y * (tileHeightPx + spacing)
        return TextureRegion(texture, px, py, tileWidthPx, tileHeightPx)
    }

    /** Disposes the backing texture. */
    override fun dispose() = texture.dispose()

    private companion object {
        /** Smallest power of two >= [value] (and >= 1). */
        fun nextPowerOfTwo(value: Int): Int {
            var n = 1
            while (n < value) n = n shl 1
            return n
        }

        /**
         * Returns a pixmap [zeroOutColor] can actually key into: RGBA8888 when a [keyColor] is set,
         * the decoded pixmap untouched otherwise.
         *
         * `Pixmap(FileHandle)` does **not** always decode to RGBA8888 — a PNG with no alpha channel
         * decodes to [Pixmap.Format.RGB888], which has no alpha byte to write. On such a pixmap
         * [zeroOutColor]'s `drawPixel(x, y, 0)` only rewrites the (already-black) RGB and the keyed
         * background uploads fully opaque, so cell backgrounds are hidden behind an opaque glyph
         * surround (krogue-7i8). The bundled 12x12/16x16/9x16 sheets are stored without alpha and hit
         * exactly this; 8x8/10x10 happen to carry an alpha channel and keyed correctly, which is why
         * the symptom looked tied to tile size rather than to the source format it actually is.
         *
         * Promoting to RGBA8888 first (via [Pixmap.drawPixmap] with blending off, so the opaque
         * source copies in unchanged) gives keying a real alpha channel to clear. Only done when a
         * key is requested: an un-keyed RGB888 sheet is meant to stay fully opaque, and the
         * power-of-two padding below already produces an RGBA8888 upload for the NPOT case.
         */
        fun decodeForKeying(
            decoded: Pixmap,
            keyColor: Color?,
        ): Pixmap {
            if (keyColor == null || decoded.format == Pixmap.Format.RGBA8888) return decoded
            return Pixmap(decoded.width, decoded.height, Pixmap.Format.RGBA8888).apply {
                blending = Pixmap.Blending.None
                drawPixmap(decoded, 0, 0)
                decoded.dispose()
            }
        }

        /**
         * Makes every pixel whose RGB matches [keyColor] fully transparent, so a sheet authored on a
         * solid background composites cleanly. Reads each pixel with [Pixmap.getPixel] and clears the
         * matches with [Pixmap.drawPixel], both addressed by (x, y).
         *
         * Staying on libGDX's coordinate API for both read and write is deliberate and load-bearing:
         * writing transparency straight into `Pixmap.pixels` — the backing buffer, by any means,
         * per-byte or bulk — corrupts pixels when the [Pixmap] is later uploaded to a [Texture]. The
         * red byte of the pixel *following* a keyed one is dropped, so white glyph pixels next to the
         * keyed background render cyan on thin strokes (a green/teal tint, krogue-9gu). [Pixmap.drawPixel]
         * goes through libGDX's own native path and is correct. Addressing by (x, y) also never touches
         * the buffer's position, so an already-power-of-two sheet (uploaded as-is) can't upload blank
         * (krogue-3wr).
         *
         * Assumes RGBA8888 layout (4 bytes per pixel: R, G, B, A) — required, not incidental: on an
         * alpha-less format the `drawPixel` below has no alpha byte to clear and keying silently
         * no-ops (krogue-7i8). [decodeForKeying] guarantees the layout before this runs; do not call
         * this on a raw `Pixmap(FileHandle)`, which may be [Pixmap.Format.RGB888] for a no-alpha PNG.
         */
        fun zeroOutColor(
            pixmap: Pixmap,
            keyColor: Color,
        ) {
            // Compare the top 24 bits (RGB) of the RGBA8888 pixel, ignoring alpha.
            val keyRgb = (Color.rgba8888(keyColor).toLong() and 0xFFFFFFFFL) and 0xFFFFFF00L

            // Key entirely through libGDX's own getPixel/drawPixel, addressed by (x, y). Writing
            // transparency straight into Pixmap.pixels (the backing buffer) corrupts pixels on the
            // texture upload — the red byte of the pixel following a keyed one is dropped, turning
            // white glyph pixels cyan on thin strokes (a green/teal tint, krogue-9gu). An earlier
            // attempt that indexed the raw buffer by byte offset garbled glyphs; staying in (x, y)
            // for both read and write is the correct, simple path. Addressing by (x, y) also never
            // touches the buffer position, so already-POT sheets can't upload blank (krogue-3wr).
            pixmap.blending = Pixmap.Blending.None
            for (y in 0 until pixmap.height) {
                for (x in 0 until pixmap.width) {
                    if ((pixmap.getPixel(x, y).toLong() and 0xFFFFFFFFL and 0xFFFFFF00L) == keyRgb) {
                        pixmap.drawPixel(x, y, 0) // fully transparent (0,0,0,0)
                    }
                }
            }
        }
    }
}
