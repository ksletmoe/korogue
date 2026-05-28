package com.sletmoe.krogue.kotile

import com.badlogic.gdx.graphics.Color as GdxColor
import java.awt.Color as AwtColor

/**
 * Boundary shim: converts [java.awt.Color] to [com.badlogic.gdx.graphics.Color].
 *
 * This file exists solely to bridge the krogue model (AWT-typed) to kotile (GDX-typed)
 * during Phase 3b. Phase 3c migrates the model's color fields away from AWT and
 * deletes this file entirely. Keep all AWT→GDX conversion here so 3c has a single
 * deletion target.
 */
internal fun AwtColor.toGdxColor(): GdxColor = GdxColor(red / 255f, green / 255f, blue / 255f, alpha / 255f)
