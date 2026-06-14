package com.sletmoe.korogue.perception

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The engine's built-in concrete Concealment / Suppressor components (ADR-0015) — the payload that
 * plugs into the two-phase reveal/suppress seam whose interfaces ([Concealment], [Suppressor]) and
 * algorithm ([StandardPerception]) landed in 1my.1. They carry no behaviour: a concealment or
 * suppressor is fully described by the [Sense] tags it negates, so each is a tiny tagged marker
 * (a `data object`, like `Player`), registered for save/load in `GameModule.engineDefaults()` and
 * overridable/extendable by a game declaring its own.
 *
 * Suppressors live on the **observer** and remove its own senses; concealments live on the **target**
 * and hide it from observers. A [Sense.pierces]d tag defeats a **concealment** (see-invisible), but a
 * **suppressor** is absolute on its channel — pierces does not apply (ADR-0015), so a sense escapes a
 * suppressor only by being on a tag it doesn't negate. Modelling them as *components* (rather than as
 * deletion of a `Sight`) is what makes a transient effect scheduler-expirable: a fuse (krogue-6uq)
 * removes the [Blind] component to restore an otherwise-untouched `Sight`, instead of reconstructing one.
 */

/**
 * Concealment hiding its owner from `{visual}` senses — the classic **invisible monster**. `Sight` and
 * `Darkvision` can't perceive it; the cell it stands on is still seen (concealment hides the entity,
 * not the terrain). Tremorsense/Telepathy still feel it (different tag), and a piercing visual sense
 * like [TrueSight] sees it. Dataless marker.
 */
@Serializable
@SerialName("invisible")
data object Invisible : Concealment {
    override val concealsFromTags = setOf(PerceptionTags.VISUAL)
}

/**
 * Observer suppressor that negates `{visual}` senses — **blindness**. While present, *every* `{visual}`
 * sense (`Sight`, `Darkvision`, even `TrueSight`) contributes nothing — suppression is absolute on its
 * channel, so piercing does not save them; only a non-`{visual}` sense (`Tremorsense`, …) keeps
 * perceiving. Because it is a separate component, a *transient* blind drops perception without deleting
 * the permanent `Sight`, and a scheduler fuse (krogue-6uq) can remove it to restore sight cleanly.
 * Dataless marker.
 */
@Serializable
@SerialName("blind")
data object Blind : Suppressor {
    override val negatesTags = setOf(PerceptionTags.VISUAL)
}

/**
 * Observer suppressor that negates `{visual}` senses — **dazzled** (flash-blinded, snow-blind, …).
 * Mechanically identical to [Blind] today (both kill visual senses); it exists as a distinct component
 * so a game can target, message, or expire the two independently. Dataless marker.
 */
@Serializable
@SerialName("dazzled")
data object Dazzled : Suppressor {
    override val negatesTags = setOf(PerceptionTags.VISUAL)
}
