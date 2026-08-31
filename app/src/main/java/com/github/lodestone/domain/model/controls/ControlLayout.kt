package com.github.lodestone.domain.model.controls

import kotlinx.serialization.Serializable

/**
 * One thing the player can touch, and what it does.
 *
 * The set is Pocket Edition's, because that is the layout this is meant to feel like. Two of its
 * buttons have no counterpart here and are absent: Bedrock's hotbar is drawn by its own HUD, and
 * Java draws its own, so there is nothing for us to place; and Bedrock's "use" is a tap on the
 * world rather than a button, which is how the camera handles it here too.
 */
@Serializable
enum class ControlId {
    /** The movement stick. Sized and placed like the others, but drawn and driven differently. */
    STICK,
    JUMP,
    SNEAK,
    SPRINT,
    ATTACK,
    INVENTORY,
    CHAT,
    DROP,
    PAUSE,
    DEBUG,
    ;

    /** Whether this is the stick, which has no pressed state and no single key behind it. */
    val isStick: Boolean get() = this == STICK
}

/**
 * Where one control sits and how big it is.
 *
 * The position is the centre, as a fraction of the window, rather than a margin in pixels. A layout
 * is a thing a player arranges once and keeps, and it has to survive the window changing under it —
 * a fold opening, a different phone, the same phone turned around. Anchoring to a corner would
 * work for the corner it was placed in and drift for every other.
 *
 * The size stays in density-independent pixels, because a button is sized to a thumb and a thumb is
 * the same size on every screen.
 */
@Serializable
data class ControlPlacement(
    val id: ControlId,
    val x: Float,
    val y: Float,
    val size: Float,
    val visible: Boolean = true,
) {
    fun coerced(): ControlPlacement = copy(
        x = x.coerceIn(0f, 1f),
        y = y.coerceIn(0f, 1f),
        size = size.coerceIn(MINIMUM_SIZE, MAXIMUM_SIZE),
    )

    companion object {
        const val MINIMUM_SIZE = 32f
        const val MAXIMUM_SIZE = 200f
    }
}

/**
 * A whole arrangement of controls.
 *
 * Stored as a list rather than a map so that the order it is drawn in is the order it is written
 * in, which is what decides who wins where two overlap — something a player is allowed to do.
 */
@Serializable
data class ControlLayout(val placements: List<ControlPlacement> = DEFAULT) {

    operator fun get(id: ControlId): ControlPlacement? = placements.firstOrNull { it.id == id }

    fun with(placement: ControlPlacement): ControlLayout = copy(
        placements = placements.map { if (it.id == placement.id) placement.coerced() else it },
    )

    /**
     * The layout with anything this build has since added filled in from the default.
     *
     * A stored layout is written by whichever version the player last arranged it with, so a new
     * control would otherwise be missing from every layout that predates it — invisible, with
     * nothing to say why.
     */
    fun completed(): ControlLayout {
        val known = placements.map { it.id }.toSet()
        val missing = DEFAULT.filterNot { it.id in known }
        return if (missing.isEmpty()) this else copy(placements = placements + missing)
    }

    companion object {
        /**
         * Pocket Edition's arrangement, as closely as it transfers.
         *
         * The stick sits under the left thumb and jump under the right, with the buttons a hand
         * reaches without leaving either. The right-hand column climbs away from jump in the order
         * they are wanted mid-action: sneak beside it, attack above, sprint above that.
         *
         * The top edge holds what is not part of playing — pause, chat, and the debug screen —
         * where a thumb does not rest, and clear of the top right, which is where Minecraft slides
         * its own toasts in.
         */
        val DEFAULT: List<ControlPlacement> = listOf(
            ControlPlacement(ControlId.STICK, x = 0.125f, y = 0.700f, size = 140f),
            ControlPlacement(ControlId.JUMP, x = 0.930f, y = 0.790f, size = 76f),
            ControlPlacement(ControlId.SNEAK, x = 0.820f, y = 0.830f, size = 56f),
            ControlPlacement(ControlId.ATTACK, x = 0.930f, y = 0.560f, size = 64f),
            ControlPlacement(ControlId.SPRINT, x = 0.820f, y = 0.600f, size = 56f),
            ControlPlacement(ControlId.INVENTORY, x = 0.962f, y = 0.310f, size = 50f),
            ControlPlacement(ControlId.DROP, x = 0.962f, y = 0.150f, size = 44f),
            ControlPlacement(ControlId.CHAT, x = 0.500f, y = 0.070f, size = 44f),
            ControlPlacement(ControlId.PAUSE, x = 0.035f, y = 0.080f, size = 44f),
            ControlPlacement(ControlId.DEBUG, x = 0.105f, y = 0.080f, size = 44f),
        )

        val Default = ControlLayout(DEFAULT)
    }
}
