package com.github.lodestone.ui.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.lodestone.runtime.GlfwBridge
import com.github.lodestone.runtime.GlfwKeys
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * The on-screen controls drawn over the game.
 *
 * Every control resolves to a GLFW key or mouse event, so the game sees exactly what a desktop
 * player's keyboard and mouse would send and nothing Minecraft-side has to change.
 *
 * The layout follows the one Pocket Edition settled on, because it is the one players already know:
 * a thumbstick under the left thumb, jump under the right, and the rest of the screen as the camera.
 * On the world, a tap uses or places and a press-and-hold mines — the two things a mouse does with
 * its buttons, told apart here by how long a finger stays still.
 *
 * What has no counterpart on Bedrock is the mode switch. Java Edition grabs the pointer while you
 * are playing and lets it go whenever a screen is open, and those two states want opposite things
 * from a touchscreen: relative movement and held keys in one, an absolute cursor that can drag an
 * item across a grid in the other. So the overlay is really two overlays, and [GlfwBridge] reports
 * which one the game is asking for.
 */
@Composable
fun TouchControls(
    modifier: Modifier = Modifier,
    opacity: Float = DEFAULT_OPACITY,
    onOpenMenu: () -> Unit = {},
) {
    // Polled rather than pushed: the grab is the game's to change, it changes on the render thread
    // inside `glfwSetInputMode`, and there is nothing on that path that could call back into
    // Compose. A tenth of a second is far below the time it takes a player to reach for a button
    // after a screen opens.
    val grabbed by produceState(initialValue = true) {
        while (true) {
            value = GlfwBridge.isCursorGrabbed()
            delay(GRAB_POLL_MILLIS)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (grabbed) {
            PlayingControls(opacity = opacity, onOpenMenu = onOpenMenu)
        } else {
            ScreenControls(opacity = opacity, onOpenMenu = onOpenMenu)
        }
    }
}

/** The overlay while the game has the pointer: a thumbstick, a camera, and the action buttons. */
@Composable
private fun BoxScope.PlayingControls(
    opacity: Float,
    onOpenMenu: () -> Unit,
) {
    // Underneath everything, so a drag that starts on a button never turns the camera as well.
    CameraArea(modifier = Modifier.fillMaxSize())

    Thumbstick(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(EDGE_PADDING)
            .alpha(opacity),
    )

    HotbarStrip(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = HOTBAR_BOTTOM_PADDING)
            .alpha(opacity),
    )

    Row(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(EDGE_PADDING)
            .alpha(opacity),
        horizontalArrangement = Arrangement.spacedBy(GAP),
        verticalAlignment = Alignment.Bottom,
    ) {
        RoundHoldButton(
            label = "☰",
            diameter = SMALL_BUTTON,
            onPress = { GlfwBridge.sendKey(GlfwKeys.E, 0, GlfwBridge.Action.PRESS) },
            onRelease = { GlfwBridge.sendKey(GlfwKeys.E, 0, GlfwBridge.Action.RELEASE) },
        )
        // Sneak latches rather than being held. On a keyboard a thumb can rest on shift while the
        // other hand does everything else; here the same thumb is needed for jump, and Bedrock
        // latches it for the same reason.
        RoundToggleButton(
            label = "▼",
            diameter = SMALL_BUTTON,
            key = GlfwKeys.LEFT_SHIFT,
        )
        // Jump is the biggest thing on the right, where Bedrock puts it and where a right thumb
        // rests without moving off the camera.
        RoundHoldButton(
            label = "▲",
            diameter = JUMP_BUTTON,
            onPress = { GlfwBridge.sendKey(GlfwKeys.SPACE, 0, GlfwBridge.Action.PRESS) },
            onRelease = { GlfwBridge.sendKey(GlfwKeys.SPACE, 0, GlfwBridge.Action.RELEASE) },
        )
    }

    // Top left, where Bedrock puts its pause button, and — more to the point — away from the top
    // right, which is where Minecraft slides its own toasts in and would sit under these.
    SystemButtons(
        onOpenMenu = onOpenMenu,
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(EDGE_PADDING)
            .alpha(opacity),
    )
}

/**
 * The overlay while a screen is open: the whole surface is a cursor.
 *
 * None of the playing controls are drawn. There is nothing for them to do — the game ignores every
 * movement key while a screen has focus — and they would sit on top of the very inventory slots the
 * player is reaching for.
 */
@Composable
private fun BoxScope.ScreenControls(
    opacity: Float,
    onOpenMenu: () -> Unit,
) {
    CursorArea(modifier = Modifier.fillMaxSize())

    SystemButtons(
        onOpenMenu = onOpenMenu,
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(EDGE_PADDING)
            .alpha(opacity),
    )
}

/**
 * Turns touches into the camera, mining and using.
 *
 * A finger that travels becomes camera movement; one that stays put and is lifted quickly is a use
 * or a place; one that stays put and stays down is a held left button, which is what mining is. The
 * same finger can do two of those in turn — Bedrock lets you keep looking around while you break a
 * block, and so does this.
 */
@Composable
private fun CameraArea(modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    // Touch travel is in pixels and the game wants mouse counts. Without scaling by density the
    // same swipe would turn much further on a denser panel than on a coarse one.
    val sensitivity = remember(density) { LOOK_SENSITIVITY / density.density }
    val slop = remember(density) { with(density) { TAP_SLOP.toPx() } }

    Box(
        modifier = modifier.pointerInput(sensitivity, slop) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                var last = down.position
                var travel = 0f
                var looking = false
                var mining = false
                val downAt = System.currentTimeMillis()

                while (true) {
                    // Until this gesture has committed to being a look or a mine, the wait is
                    // bounded: a finger that simply rests is the one input that produces no events
                    // at all, and it is the one that means "start mining".
                    val event = if (looking || mining) {
                        awaitPointerEvent()
                    } else {
                        val remaining = MINE_HOLD_MILLIS - (System.currentTimeMillis() - downAt)
                        if (remaining <= 0) null else withTimeoutOrNull(remaining) {
                            awaitPointerEvent()
                        }
                    }

                    if (event == null) {
                        mining = true
                        GlfwBridge.sendMouseButton(
                            GlfwBridge.MouseButton.LEFT,
                            GlfwBridge.Action.PRESS,
                        )
                        continue
                    }

                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    if (!change.pressed) {
                        break
                    }

                    val delta = change.position - last
                    last = change.position
                    travel += delta.getDistance()
                    if (travel > slop) {
                        looking = true
                    }
                    if (looking) {
                        GlfwBridge.sendCursorDelta(delta.x * sensitivity, delta.y * sensitivity)
                    }
                }

                when {
                    mining -> GlfwBridge.sendMouseButton(
                        GlfwBridge.MouseButton.LEFT,
                        GlfwBridge.Action.RELEASE,
                    )
                    // Lifted before the hold threshold and without travelling: a tap on the world,
                    // which on Bedrock places a block or uses what is in hand.
                    !looking -> {
                        GlfwBridge.sendMouseButton(
                            GlfwBridge.MouseButton.RIGHT,
                            GlfwBridge.Action.PRESS,
                        )
                        GlfwBridge.sendMouseButton(
                            GlfwBridge.MouseButton.RIGHT,
                            GlfwBridge.Action.RELEASE,
                        )
                    }
                }
            }
        },
    )
}

/**
 * Drives the game's own cursor while a screen is open.
 *
 * The button goes down with the finger and up when it lifts, rather than as one click on release,
 * because that is what dragging a stack across an inventory needs — and because a screen that
 * wanted a click rather than a drag cannot tell the difference.
 *
 * Positions go across unscaled: the surface the game renders into is this same window, so a touch
 * at a pixel is a cursor at that pixel.
 */
@Composable
private fun CursorArea(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                GlfwBridge.sendCursorPos(down.position.x, down.position.y)
                GlfwBridge.sendMouseButton(
                    GlfwBridge.MouseButton.LEFT,
                    GlfwBridge.Action.PRESS,
                )

                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    if (!change.pressed) {
                        break
                    }
                    GlfwBridge.sendCursorPos(change.position.x, change.position.y)
                }

                GlfwBridge.sendMouseButton(
                    GlfwBridge.MouseButton.LEFT,
                    GlfwBridge.Action.RELEASE,
                )
            }
        },
    )
}

/**
 * The movement stick.
 *
 * Eight-way rather than analogue, because the keys it stands in for are: Minecraft has no half-
 * pressed W. The dead zone keeps a thumb resting on the stick from walking, and pushing it to the
 * rim runs — which is where Bedrock puts sprint too, and saves a button.
 */
@Composable
private fun Thumbstick(modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val radius = remember(density) { with(density) { (STICK_SIZE / 2).toPx() } }
    var knob by remember { mutableStateOf(Offset.Zero) }
    val held = remember { mutableSetOf<Int>() }

    /** Presses what should be down and releases what should not, so no key is sent twice. */
    fun apply(keys: Set<Int>) {
        (held - keys).forEach { GlfwBridge.sendKey(it, 0, GlfwBridge.Action.RELEASE) }
        (keys - held).forEach { GlfwBridge.sendKey(it, 0, GlfwBridge.Action.PRESS) }
        held.clear()
        held.addAll(keys)
    }

    // A finger still on the stick when the game takes a screen — or when the activity goes away —
    // would otherwise leave the player walking into a wall on the way back.
    DisposableEffect(Unit) {
        onDispose { apply(emptySet()) }
    }

    Box(
        modifier = modifier
            .size(STICK_SIZE)
            .pointerInput(radius) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val centre = Offset(size.width / 2f, size.height / 2f)

                    var position = down.position
                    while (true) {
                        val offset = position - centre
                        val distance = hypot(offset.x, offset.y)
                        val clamped = if (distance > radius && distance > 0f) {
                            offset * (radius / distance)
                        } else {
                            offset
                        }
                        knob = clamped

                        val normalised = if (radius > 0f) clamped / radius else Offset.Zero
                        apply(keysFor(normalised))

                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) {
                            break
                        }
                        position = change.position
                    }

                    knob = Offset.Zero
                    apply(emptySet())
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centre = Offset(size.width / 2f, size.height / 2f)
            drawCircle(color = STICK_BASE, radius = size.minDimension / 2f, center = centre)
            drawCircle(
                color = BUTTON_BORDER,
                radius = size.minDimension / 2f,
                center = centre,
                style = Stroke(width = 2f),
            )
            drawCircle(
                color = STICK_KNOB,
                radius = size.minDimension / 6f,
                center = centre + knob,
            )
        }
    }
}

/**
 * Which keys a stick offset stands for.
 *
 * Each axis is decided on its own, which is what makes the diagonals fall out rather than needing
 * eight cases of their own.
 */
private fun keysFor(normalised: Offset): Set<Int> {
    val magnitude = min(1f, hypot(normalised.x, normalised.y))
    if (magnitude < STICK_DEAD_ZONE) {
        return emptySet()
    }

    val keys = mutableSetOf<Int>()
    if (normalised.y < -STICK_AXIS_THRESHOLD) keys += GlfwKeys.W
    if (normalised.y > STICK_AXIS_THRESHOLD) keys += GlfwKeys.S
    if (normalised.x < -STICK_AXIS_THRESHOLD) keys += GlfwKeys.A
    if (normalised.x > STICK_AXIS_THRESHOLD) keys += GlfwKeys.D
    // Pushed to the rim and going forwards: run. Held as a key rather than toggled, so letting go
    // of the stick stops the sprint with it.
    if (magnitude > STICK_SPRINT_THRESHOLD && normalised.y < -STICK_AXIS_THRESHOLD) {
        keys += GlfwKeys.LEFT_CONTROL
    }
    return keys
}

/**
 * A strip over the game's own hotbar that scrolls it.
 *
 * Minecraft draws the hotbar itself, centred at the bottom, and while the pointer is grabbed there
 * is no way to click a slot. A mouse wheel is how a desktop player changes slots, so a swipe here
 * sends wheel notches — landing on the one part of the screen where a player would already be
 * reaching to change what is in hand.
 */
@Composable
private fun HotbarStrip(modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val step = remember(density) { with(density) { HOTBAR_STEP.toPx() } }

    Box(
        modifier = modifier
            .width(HOTBAR_WIDTH)
            .height(HOTBAR_HEIGHT)
            .pointerInput(step) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var anchor = down.position.x

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) {
                            break
                        }
                        val travelled = change.position.x - anchor
                        if (abs(travelled) >= step) {
                            // Right along the hotbar is the next slot, which is a wheel notch down.
                            val notches = (travelled / step).toInt()
                            GlfwBridge.sendScroll(0f, -notches.toFloat())
                            anchor += notches * step
                        }
                    }
                }
            },
    )
}

@Composable
private fun BoxScope.SystemButtons(
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(GAP)) {
        RoundHoldButton(
            label = "F3",
            diameter = SMALL_BUTTON,
            onPress = { GlfwBridge.sendKey(GlfwKeys.F3, 0, GlfwBridge.Action.PRESS) },
            onRelease = { GlfwBridge.sendKey(GlfwKeys.F3, 0, GlfwBridge.Action.RELEASE) },
        )
        RoundHoldButton(
            label = "T",
            diameter = SMALL_BUTTON,
            onPress = { GlfwBridge.sendKey(GlfwKeys.T, 0, GlfwBridge.Action.PRESS) },
            onRelease = { GlfwBridge.sendKey(GlfwKeys.T, 0, GlfwBridge.Action.RELEASE) },
        )
        RoundHoldButton(
            label = "Esc",
            diameter = SMALL_BUTTON,
            onPress = { GlfwBridge.sendKey(GlfwKeys.ESCAPE, 0, GlfwBridge.Action.PRESS) },
            onRelease = { GlfwBridge.sendKey(GlfwKeys.ESCAPE, 0, GlfwBridge.Action.RELEASE) },
        )
        Box(
            modifier = Modifier
                .size(SMALL_BUTTON)
                .clip(CircleShape)
                .background(BUTTON_BACKGROUND)
                .border(1.dp, BUTTON_BORDER, CircleShape)
                .pointerInput(Unit) { detectTapGestures { onOpenMenu() } },
            contentAlignment = Alignment.Center,
        ) {
            // Two bars, as Bedrock's pause button is. Written out rather than taken from a symbol
            // font: the glyph this used to be rendered as a missing-character box on the device.
            Text("II", color = Color.White, fontSize = LABEL_SIZE, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * A round button that latches its key down until it is touched again.
 *
 * The key is released on the way out as well as on the second tap: a latch that survived the
 * screen it was set on would leave the player crouching with no button left to un-crouch with.
 */
@Composable
private fun RoundToggleButton(label: String, diameter: Dp, key: Int) {
    var latched by remember { mutableStateOf(false) }

    DisposableEffect(key) {
        onDispose {
            if (latched) {
                GlfwBridge.sendKey(key, 0, GlfwBridge.Action.RELEASE)
            }
        }
    }

    Box(
        modifier = Modifier
            .size(diameter)
            .clip(CircleShape)
            .background(if (latched) BUTTON_LATCHED else BUTTON_BACKGROUND)
            .border(1.dp, BUTTON_BORDER, CircleShape)
            .pointerInput(key) {
                detectTapGestures(
                    onTap = {
                        latched = !latched
                        GlfwBridge.sendKey(
                            key,
                            0,
                            if (latched) GlfwBridge.Action.PRESS else GlfwBridge.Action.RELEASE,
                        )
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = LABEL_SIZE,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** A round button that holds its key or mouse button down for as long as it is touched. */
@Composable
private fun RoundHoldButton(
    label: String,
    diameter: Dp,
    onPress: () -> Unit,
    onRelease: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(diameter)
            .clip(CircleShape)
            .background(BUTTON_BACKGROUND)
            .border(1.dp, BUTTON_BORDER, CircleShape)
            .pointerInput(label) {
                detectTapGestures(
                    onPress = {
                        onPress()
                        // The release has to fire even if the finger slides off the button, or the
                        // key would stay stuck down.
                        tryAwaitRelease()
                        onRelease()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = LABEL_SIZE,
            fontWeight = FontWeight.Medium,
        )
    }
}

private val BUTTON_BACKGROUND = Color(0x66000000)
private val BUTTON_BORDER = Color(0x99FFFFFF)
private val BUTTON_LATCHED = Color(0x99FFFFFF)
private val STICK_BASE = Color(0x40000000)
private val STICK_KNOB = Color(0x99FFFFFF)

private val EDGE_PADDING = 20.dp
private val GAP = 12.dp
private val LABEL_SIZE = 13.sp

private val STICK_SIZE = 132.dp
private val JUMP_BUTTON = 68.dp
private val SMALL_BUTTON = 44.dp

private val HOTBAR_WIDTH = 260.dp
private val HOTBAR_HEIGHT = 44.dp
private val HOTBAR_BOTTOM_PADDING = 4.dp

/** How far a finger travels along the hotbar before it counts as one slot. */
private val HOTBAR_STEP = 28.dp

/** How far a finger may travel and still count as a tap rather than a look. */
private val TAP_SLOP = 12.dp

/** How long a still finger waits before it becomes a held left button, in milliseconds. */
private const val MINE_HOLD_MILLIS = 180L

private const val GRAB_POLL_MILLIS = 100L

/** Mouse counts per density-independent pixel of travel. */
private const val LOOK_SENSITIVITY = 1.4f

private const val STICK_DEAD_ZONE = 0.28f
private const val STICK_AXIS_THRESHOLD = 0.38f
private const val STICK_SPRINT_THRESHOLD = 0.92f

private const val DEFAULT_OPACITY = 0.75f
