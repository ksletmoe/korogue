package com.sletmoe.korogue.perception

/**
 * The engine's built-in perception **tag ids** (ADR-0015). A [Sense] declares the tags describing what
 * it *is* or *depends on*; a [Concealment] or [Suppressor] negates senses by matching these tags, and a
 * sense [Sense.pierces] the tags it sees through anyway. Selective interaction is thus expressed by
 * tags rather than a sense×effect matrix.
 *
 * Tags are deliberately plain string ids with **no registry** (ADR-0015): a tag carries no behaviour to
 * resolve, so a registry would be validation overhead redundant with the string-id convention. A game
 * declares its own tags the same way — as constants — and can target the engine's by referencing these.
 */
object PerceptionTags {
    /** Sight-like senses that work by seeing. Negated by visual concealment (`Invisible`) and blindness. */
    const val VISUAL = "visual"

    /** Refines [VISUAL]: this sense needs the cell lit. `Sight` carries it; `Darkvision` does not. */
    const val LIGHT_DEPENDENT = "light-dependent"

    /** Senses that work by ground vibration (`Tremorsense`). A future `Silenced` would negate it. */
    const val VIBRATION = "vibration"

    /** Senses that work by reading minds (`Telepathy`). A future mind-shield concealment would negate it. */
    const val MENTAL = "mental"
}
