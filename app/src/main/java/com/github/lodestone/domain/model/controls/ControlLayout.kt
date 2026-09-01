package com.github.lodestone.domain.model.controls

import kotlinx.serialization.Serializable

/**
 * One thing the player can touch, and what it does.
 *
 * The set is Pocket Edition's, and which buttons that means is not a guess: its native library
 * names the textures its touch controls draw from, and they are exactly `joystick_frame`,
 * `joystick_knob`, `jump`, `sneak`, `sprint`, `attack`, `interact` and `pick_block`. Everything
 * here up to [PICK_BLOCK] is one of those.
 *
 * Every one of them does something in Java. Bedrock's other touch buttons — mount and dismount,
 * ascend and descend in air and in water, the ride and exit pair — are all for states Java has no
 * key for at all, so they are not here: a button that cannot be pressed into anything is worse than
 * a missing one, because it looks like it should work.
 *
 * The rest are ours, and they exist because Java needs them where Bedrock does not. Bedrock reaches
 * its inventory from the end of its own hotbar, which Java draws itself and we cannot put a button
 * inside; its chat and pause live in a HUD row that is not a touch control at all; and it has no
 * drop key or debug screen to offer.
 */
@Serializable
enum class ControlId {
    /** The movement stick. Sized and placed like the others, but drawn and driven differently. */
    STICK,
    JUMP,
    SNEAK,
    SPRINT,
    ATTACK,
    /** Bedrock's use button. Java's right mouse button: place a block, open a door, eat. */
    INTERACT,
    /** Bedrock's pick-block button, which is Java's middle mouse button. */
    PICK_BLOCK,
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
         * Pocket Edition's arrangement, measured off Pocket Edition.
         *
         * Not estimated by eye and not invented: the positions below come from segmenting a frame
         * of Bedrock 1.26 running on a 2670x1200 panel, isolating the control plates by their
         * desaturation against the world behind them, and taking the centre of each. They are not
         * readable anywhere else — the touch layout is not in Bedrock's JSON UI, which carries only
         * its gamepad glyphs, and in the native library it survives as float constants inside
         * `TouchMoveAndTurnInteractControl` and friends rather than as anything nameable.
         *
         * So: the stick sits left of centre and halfway up, not in the corner. Jump, sprint and
         * sneak are a triangle on the right at the same height as the stick, an arrangement a
         * thumb pivots through rather than a row it slides along. The three small buttons ride the
         * top edge, centred. Nothing sits in the bottom corners at all, which is the part that is
         * least obvious and most of why this reads as Bedrock.
         *
         * Sizes are in dp, converted at the 2.6 density that panel reports.
         */
        val DEFAULT: List<ControlPlacement> = listOf(
            ControlPlacement(ControlId.STICK, x = 0.149f, y = 0.546f, size = 149f),
            ControlPlacement(ControlId.JUMP, x = 0.925f, y = 0.394f, size = 58f),
            ControlPlacement(ControlId.SPRINT, x = 0.826f, y = 0.494f, size = 58f),
            ControlPlacement(ControlId.SNEAK, x = 0.925f, y = 0.585f, size = 58f),
            // Bedrock shows interact only when something is worth interacting with, which needs a
            // hit test we cannot run from out here. Java's right button is worth a button of its
            // own regardless — tapping the world places a block, but not accurately.
            ControlPlacement(ControlId.INTERACT, x = 0.826f, y = 0.394f, size = 58f),
            // Bedrock's top row is emote, chat and menu. Java has no emote, so the middle slot goes
            // to the inventory — which Bedrock reaches from the end of its own hotbar, and Java
            // cannot, because Java draws that hotbar itself.
            ControlPlacement(ControlId.CHAT, x = 0.472f, y = 0.033f, size = 25f),
            ControlPlacement(ControlId.INVENTORY, x = 0.500f, y = 0.033f, size = 25f),
            ControlPlacement(ControlId.PAUSE, x = 0.528f, y = 0.033f, size = 25f),
            ControlPlacement(ControlId.ATTACK, x = 0.826f, y = 0.585f, size = 58f),
            // Java can middle-click to pick a block, drop with Q and open the debug screen with F3,
            // so all three do something — they are simply not what a thumb reaches for mid-fight.
            // Hidden rather than absent: one tap in the editor puts any of them on screen.
            ControlPlacement(
                ControlId.PICK_BLOCK, x = 0.725f, y = 0.494f, size = 58f, visible = false,
            ),
            ControlPlacement(ControlId.DROP, x = 0.826f, y = 0.585f, size = 58f, visible = false),
            ControlPlacement(ControlId.DEBUG, x = 0.440f, y = 0.033f, size = 25f, visible = false),
        )

        val Default = ControlLayout(DEFAULT)
    }
}
