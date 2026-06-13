package com.sletmoe.korogue.perception

import com.sletmoe.korogue.ecs.Component

/**
 * The two marker components of perception's **suppress phase** (ADR-0015). Most senses are additive
 * (they reveal more); the hard cases *remove* perception, and are modelled as components negating
 * tags rather than as negative senses. [StandardPerception] applies them after unioning every sense,
 * so phase one stays order-independent and all precedence lives in one place. The engine's concrete
 * suppressors and concealments (`Blind`, `Invisible`, …) arrive in krogue-1my.3; these interfaces are
 * the seam they plug into.
 *
 * A sense survives a negation when it [Sense.pierces] the matched tag — see-invisible is a visual
 * sense that pierces `Invisible`'s tag.
 */

/**
 * A component on the **observer** that removes its own perception by negating sense [tags it negates]
 * — `Blind` negates `{visual}`, `Dazzled` …. Because it is a separate component, a *transient* blind
 * effect suppresses without deleting a permanent `Sight`, and a scheduler (krogue-6uq) can expire it
 * to restore sight cleanly.
 */
interface Suppressor : Component {
    /** Sense tags this suppressor negates on its owner (a sense with a matched, unpierced tag is dropped). */
    val negatesTags: Set<String>
}

/**
 * A component on the **target** that hides it from senses whose [tags it conceals from] it matches —
 * `Invisible` conceals from `{visual}`, a future `Silenced` from `{vibration}`. A new concealment
 * targets tags and automatically affects all matching senses, with no sense×effect table. A sense
 * that [Sense.pierces] the matched tag perceives the target anyway.
 */
interface Concealment : Component {
    /** Sense tags this concealment hides its owner from (a matching, unpiercing sense can't see it). */
    val concealsFromTags: Set<String>
}
