package com.github.lodestone.ui.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.lodestone.domain.model.controls.ControlId
import com.github.lodestone.domain.model.controls.ControlLayout
import com.github.lodestone.domain.model.controls.ControlPlacement
import com.github.lodestone.runtime.GlfwBridge
import com.github.lodestone.runtime.GlfwKeys
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The on-screen controls drawn over the game.
 *
 * Every control resolves to a GLFW key or mouse event, so the game sees exactly what a desktop
 * player's keyboard and mouse would send and nothing Minecraft-side has to change.
 *
 * The arrangement is Pocket Edition's, down to which buttons exist and where they start: a stick
 * under the left thumb, jump under the right, and the rest of the screen as the camera. On the
 * world a tap uses or places and a press-and-hold mines — the two mouse buttons, told apart by how
 * long a finger stays still. And as on Bedrock, every one of them can be moved, resized or turned
 * off; a control scheme that cannot be adjusted fits one hand and no others.
 *
 * What has no counterpart on Bedrock is the mode switch. Java grabs the pointer while you are
 * playing and releases it whenever a screen is open, and those two states want opposite things from
 * a touchscreen: relative movement and held keys in one, an absolute cursor that can drag a stack
 * across a grid in the other. So this is really two overlays, and [GlfwBridge] reports which one
 * the game is asking for.
 *
 * The stick is deliberately not analogue, and cannot be. `KeyboardInput.calculateImpulse` takes two
 * booleans and returns -1, 0 or 1; the movement vector is two of those, normalised. There is no
 * gamepad path in the client to reach for instead — it calls no GLFW joystick function at all.
 * Eight directions is the ceiling, and speed varies only by what sprint and sneak do to it.
 */
@Composable
fun TouchControls(
    layout: ControlLayout,
    editing: Boolean,
    onLayoutChange: (ControlLayout) -> Unit,
    onEditingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    opacity: Float = DEFAULT_OPACITY,
    onOpenMenu: () -> Unit = {},
) {
    // Polled rather than pushed: the grab is the game's to change, it changes on the render thread
    // inside `glfwSetInputMode`, and nothing on that path could call back into Compose. A tenth of
    // a second is far below the time it takes a player to reach for a button after a screen opens.
    val grabbed by produceState(initialValue = true) {
        while (true) {
            value = GlfwBridge.isCursorGrabbed()
            delay(GRAB_POLL_MILLIS)
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val width = maxWidth
        val height = maxHeight

        when {
            editing -> LayoutEditor(
                layout = layout,
                width = width,
                height = height,
                onLayoutChange = onLayoutChange,
                onDone = { onEditingChange(false) },
            )

            // A screen is open. None of the playing controls are drawn: the game ignores every
            // movement key while a screen has focus, and they would sit on top of the very slots
            // the player is reaching for.
            !grabbed -> {
                CursorArea(modifier = Modifier.fillMaxSize())
                layout[ControlId.PAUSE]?.let { pause ->
                    PlacedControl(
                        placement = pause,
                        width = width,
                        height = height,
                        opacity = opacity,
                        onOpenMenu = onOpenMenu,
                        onEditRequested = { onEditingChange(true) },
                    )
                }
            }

            else -> {
                // Underneath the buttons, so a drag that starts on one never turns the camera too.
                CameraArea(modifier = Modifier.fillMaxSize())
                layout.placements.filter { it.visible }.forEach { placement ->
                    PlacedControl(
                        placement = placement,
                        width = width,
                        height = height,
                        opacity = opacity,
                        onOpenMenu = onOpenMenu,
                        onEditRequested = { onEditingChange(true) },
                    )
                }
            }
        }
    }
}

/** Positions one control by its centre, which is what the layout stores. */
@Composable
private fun PlacedControl(
    placement: ControlPlacement,
    width: Dp,
    height: Dp,
    opacity: Float,
    onOpenMenu: () -> Unit,
    onEditRequested: () -> Unit,
) {
    val size = placement.size.dp
    Box(
        modifier = Modifier
            .offset(x = width * placement.x - size / 2, y = height * placement.y - size / 2)
            .alpha(opacity),
    ) {
        if (placement.id.isStick) {
            Thumbstick(size = size)
        } else {
            ControlButton(
                id = placement.id,
                size = size,
                onOpenMenu = onOpenMenu,
                onEditRequested = onEditRequested,
            )
        }
    }
}

/**
 * One button, wired to whatever it stands for.
 *
 * Sneak and sprint latch rather than being held, as they do on Bedrock and for the same reason: the
 * thumb that would have to hold them is the one that also has to reach jump.
 */
@Composable
private fun ControlButton(
    id: ControlId,
    size: Dp,
    onOpenMenu: () -> Unit,
    onEditRequested: () -> Unit,
) {
    when (id) {
        ControlId.SNEAK -> LatchingButton(id, size, GlfwKeys.LEFT_SHIFT)
        ControlId.SPRINT -> LatchingButton(id, size, GlfwKeys.LEFT_CONTROL)

        ControlId.ATTACK -> HoldButton(
            id = id,
            size = size,
            onPress = {
                GlfwBridge.sendMouseButton(GlfwBridge.MouseButton.LEFT, GlfwBridge.Action.PRESS)
            },
            onRelease = {
                GlfwBridge.sendMouseButton(GlfwBridge.MouseButton.LEFT, GlfwBridge.Action.RELEASE)
            },
        )

        // The pause button is also the way into arranging the controls, because it is the one
        // button that is never part of playing and is always on screen.
        ControlId.PAUSE -> HoldButton(
            id = id,
            size = size,
            onPress = {},
            onRelease = onOpenMenu,
            onLongPress = onEditRequested,
        )

        else -> {
            val key = when (id) {
                ControlId.JUMP -> GlfwKeys.SPACE
                ControlId.INVENTORY -> GlfwKeys.E
                ControlId.CHAT -> GlfwKeys.T
                ControlId.DROP -> GlfwKeys.Q
                ControlId.DEBUG -> GlfwKeys.F3
                else -> return
            }
            HoldButton(
                id = id,
                size = size,
                onPress = { GlfwBridge.sendKey(key, 0, GlfwBridge.Action.PRESS) },
                onRelease = { GlfwBridge.sendKey(key, 0, GlfwBridge.Action.RELEASE) },
            )
        }
    }
}

@Composable
private fun HoldButton(
    id: ControlId,
    size: Dp,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    onLongPress: (() -> Unit)? = null,
) {
    var down by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(size)
            .pointerInput(id) {
                detectTapGestures(
                    onLongPress = onLongPress?.let { handler -> { _ -> handler() } },
                    onPress = {
                        down = true
                        onPress()
                        // The release has to fire even if the finger slides off, or the key would
                        // stay down for good.
                        tryAwaitRelease()
                        down = false
                        onRelease()
                    },
                )
            },
    ) {
        ControlFace(id = id, pressed = down, size = size)
    }
}

/**
 * A button that stays down until it is touched again.
 *
 * The key is released on the way out as well as on the second tap: a latch that outlived the screen
 * it was set on would leave the player crouching with no button left to stand up with.
 */
@Composable
private fun LatchingButton(id: ControlId, size: Dp, key: Int) {
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
            .size(size)
            .pointerInput(id) {
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
    ) {
        ControlFace(id = id, pressed = latched, size = size)
    }
}

/**
 * What a button looks like.
 *
 * A texture where one is installed, and otherwise a plate and a label. Which of those a build gets
 * is decided by what is in `res/drawable`, not by anything here — see [texturesFor].
 */
@Composable
private fun ControlFace(id: ControlId, pressed: Boolean, size: Dp) {
    val textures = texturesFor(id)
    if (textures != null) {
        Image(
            painter = pixelPainter(if (pressed) textures.second else textures.first),
            contentDescription = null,
            modifier = Modifier.size(size),
        )
        return
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (pressed) BUTTON_LATCHED else BUTTON_BACKGROUND)
            .border(1.dp, BUTTON_BORDER, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = labelFor(id),
            color = if (pressed) Color.Black else Color.White,
            fontSize = LABEL_SIZE,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * The normal and pressed textures for a control, or null when none are installed.
 *
 * Resolved by name at run time rather than through generated `R` constants, so that the drawables
 * are genuinely optional: a checkout without them compiles and draws plates, and dropping a set in
 * turns them on. That makes the control artwork something that can be replaced without touching
 * this file, which is the point — it is meant to be replaced.
 *
 * A control's textures are `<name>` and `<name>_pressed`, lowercased from [ControlId].
 */
@Composable
private fun texturesFor(id: ControlId): Pair<Int, Int>? {
    val context = LocalContext.current
    return remember(id) {
        val base = id.name.lowercase()
        val normal = context.resources.getIdentifier(base, "drawable", context.packageName)
        if (normal == 0) {
            return@remember null
        }
        val pressed = context.resources
            .getIdentifier("${base}_pressed", "drawable", context.packageName)
        normal to (if (pressed == 0) normal else pressed)
    }
}

/** The stick's frame and knob, or null when this build carries neither. */
@Composable
private fun stickTextures(): Pair<Int, Int>? {
    val context = LocalContext.current
    return remember(Unit) {
        val frame = context.resources
            .getIdentifier("joystick_frame", "drawable", context.packageName)
        val knob = context.resources
            .getIdentifier("joystick_knob", "drawable", context.packageName)
        if (frame == 0 || knob == 0) null else frame to knob
    }
}

private fun labelFor(id: ControlId): String = when (id) {
    ControlId.STICK -> ""
    ControlId.JUMP -> "▲"
    ControlId.SNEAK -> "▼"
    ControlId.SPRINT -> "»"
    ControlId.ATTACK -> "✦"
    ControlId.INVENTORY -> "☰"
    ControlId.CHAT -> "T"
    ControlId.DROP -> "Q"
    ControlId.PAUSE -> "II"
    ControlId.DEBUG -> "F3"
}

/**
 * A painter for one of the reference textures, magnified without smoothing.
 *
 * These are 22-pixel sprites blown up several times over, and filtering turns their edges to mush —
 * which is the thing that makes them read as pixel art in the first place.
 */
@Composable
private fun pixelPainter(id: Int): BitmapPainter {
    val bitmap = ImageBitmap.imageResource(id)
    return remember(bitmap) { BitmapPainter(bitmap, filterQuality = FilterQuality.None) }
}

/**
 * Turns touches into the camera, mining and using.
 *
 * A finger that travels becomes camera movement; one that stays put and lifts quickly is a use or a
 * place; one that stays put and stays down is a held left button, which is what mining is. The same
 * finger can do two of those in turn — Bedrock lets you keep looking while a block breaks, and so
 * does this.
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
                    // Until this gesture has committed, the wait is bounded: a finger that simply
                    // rests produces no events at all, and that is the one that means "mine".
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
                    // Lifted before the threshold without travelling: a tap on the world, which on
                    // Bedrock places a block or uses what is in hand.
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
 * because that is what dragging a stack across an inventory needs — and a screen that wanted a
 * click rather than a drag cannot tell the difference.
 *
 * Positions cross unscaled: the surface the game renders into is this same window, so a touch at a
 * pixel is a cursor at that pixel.
 */
@Composable
private fun CursorArea(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                GlfwBridge.sendCursorPos(down.position.x, down.position.y)
                GlfwBridge.sendMouseButton(GlfwBridge.MouseButton.LEFT, GlfwBridge.Action.PRESS)

                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    if (!change.pressed) {
                        break
                    }
                    GlfwBridge.sendCursorPos(change.position.x, change.position.y)
                }

                GlfwBridge.sendMouseButton(GlfwBridge.MouseButton.LEFT, GlfwBridge.Action.RELEASE)
            }
        },
    )
}

/**
 * The movement stick.
 *
 * Eight-way rather than analogue, because the keys it stands in for are: Minecraft's movement
 * impulse is computed from two booleans and takes three values. The dead zone keeps a resting thumb
 * from walking, and pushing to the rim sprints — which is where Bedrock puts sprint too.
 */
@Composable
private fun Thumbstick(size: Dp) {
    val density = LocalDensity.current
    val radius = remember(density, size) { with(density) { (size / 2).toPx() } }
    // Held as a fraction of full deflection rather than as pixels, which is what lets the input and
    // the drawing disagree about how far "all the way" is. The finger may travel the whole radius,
    // because that is what the keys are read from; the knob may not, because it has a width and the
    // frame has an edge.
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
        modifier = Modifier
            .size(size)
            .pointerInput(radius) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val centre = Offset(this.size.width / 2f, this.size.height / 2f)

                    var position = down.position
                    while (true) {
                        val offset = position - centre
                        val distance = hypot(offset.x, offset.y)
                        val clamped = if (distance > radius && distance > 0f) {
                            offset * (radius / distance)
                        } else {
                            offset
                        }
                        val normalised = if (radius > 0f) clamped / radius else Offset.Zero
                        knob = normalised
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
        val stick = stickTextures()
        if (stick != null) {
            val frame = pixelPainter(stick.first)
            val knobArt = pixelPainter(stick.second)
            Canvas(modifier = Modifier.fillMaxSize()) {
                with(frame) { draw(this@Canvas.size) }
                val extent = this.size.minDimension / KNOB_FRACTION
                val knobSize = Size(extent, extent)
                // The knob stops where its own edge meets the frame's, so full deflection puts it
                // against the rim rather than half outside it — where the canvas would clip it.
                val travel = this.size.minDimension / 2f - extent / 2f
                translate(
                    left = (this.size.width - extent) / 2f + knob.x * travel,
                    top = (this.size.height - extent) / 2f + knob.y * travel,
                ) {
                    with(knobArt) { draw(knobSize) }
                }
            }
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val centre = Offset(this.size.width / 2f, this.size.height / 2f)
                drawCircle(color = STICK_BASE, radius = this.size.minDimension / 2f, center = centre)
                drawCircle(
                    color = BUTTON_BORDER,
                    radius = this.size.minDimension / 2f,
                    center = centre,
                    style = Stroke(width = 2f),
                )
                val knobRadius = this.size.minDimension / 6f
                drawCircle(
                    color = STICK_KNOB,
                    radius = knobRadius,
                    center = centre + knob * (this.size.minDimension / 2f - knobRadius),
                )
            }
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
    // Pushed to the rim and going forwards: run. Held rather than latched, so letting go of the
    // stick stops the sprint with it.
    if (magnitude > STICK_SPRINT_THRESHOLD && normalised.y < -STICK_AXIS_THRESHOLD) {
        keys += GlfwKeys.LEFT_CONTROL
    }
    return keys
}

/**
 * Arranging the controls, as Bedrock's own customisation screen does.
 *
 * Every control is draggable, the selected one can be resized or hidden, and nothing here reaches
 * the game: while this is open the camera and the buttons are inert, so a player cannot mine a hole
 * in the floor while moving the jump button off it.
 */
@Composable
private fun LayoutEditor(
    layout: ControlLayout,
    width: Dp,
    height: Dp,
    onLayoutChange: (ControlLayout) -> Unit,
    onDone: () -> Unit,
) {
    var selected by remember { mutableStateOf(ControlId.STICK) }
    val density = LocalDensity.current
    // The drag handler is keyed on the control's id so that it is not restarted mid-gesture, which
    // means its lambda closes over the layout as it was when the gesture began. Every delta would
    // then be applied to the same stale position and only the last one would survive — a drag
    // across the screen that moves the button a few pixels. This reads the current one instead.
    val current by rememberUpdatedState(layout)

    Box(modifier = Modifier.fillMaxSize().background(EDITOR_SCRIM)) {
        layout.placements.forEach { placement ->
            val size = placement.size.dp
            Box(
                modifier = Modifier
                    .offset(
                        x = width * placement.x - size / 2,
                        y = height * placement.y - size / 2,
                    )
                    .size(size)
                    .alpha(if (placement.visible) 1f else HIDDEN_CONTROL_ALPHA)
                    .border(
                        width = if (placement.id == selected) 2.dp else 1.dp,
                        color = if (placement.id == selected) EDITOR_SELECTED else EDITOR_OUTLINE,
                        shape = RoundedCornerShape(6.dp),
                    )
                    .pointerInput(placement.id) {
                        detectDragGestures(
                            onDragStart = { selected = placement.id },
                            onDrag = { change, drag ->
                                change.consume()
                                // Converted back into fractions immediately, so the layout never
                                // holds a position in the pixels of the screen it was arranged on.
                                val dx = with(density) { drag.x.toDp() } / width
                                val dy = with(density) { drag.y.toDp() } / height
                                val live = current
                                val moved = live[placement.id] ?: return@detectDragGestures
                                onLayoutChange(
                                    live.with(moved.copy(x = moved.x + dx, y = moved.y + dy)),
                                )
                            },
                        )
                    }
                    .pointerInput(placement.id) {
                        detectTapGestures { selected = placement.id }
                    },
            ) {
                if (placement.id.isStick) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawCircle(color = STICK_BASE, radius = this.size.minDimension / 2f)
                    }
                } else {
                    ControlFace(id = placement.id, pressed = false, size = size)
                }
            }
        }

        EditorPanel(
            layout = layout,
            selected = selected,
            onLayoutChange = onLayoutChange,
            onDone = onDone,
            modifier = Modifier.align(Alignment.BottomCenter).padding(EDGE_PADDING),
        )
    }
}

@Composable
private fun EditorPanel(
    layout: ControlLayout,
    selected: ControlId,
    onLayoutChange: (ControlLayout) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val placement = layout[selected] ?: return
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(EDITOR_PANEL)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = selected.name.lowercase().replaceFirstChar(Char::titlecase) +
                " — ${placement.size.roundToInt()} dp",
            color = Color.White,
            fontSize = LABEL_SIZE,
            fontWeight = FontWeight.Medium,
        )
        Slider(
            value = placement.size,
            onValueChange = { onLayoutChange(layout.with(placement.copy(size = it))) },
            valueRange = ControlPlacement.MINIMUM_SIZE..ControlPlacement.MAXIMUM_SIZE,
            modifier = Modifier.width(SLIDER_WIDTH),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
            EditorAction(if (placement.visible) "Hide" else "Show") {
                onLayoutChange(layout.with(placement.copy(visible = !placement.visible)))
            }
            EditorAction("Reset all") { onLayoutChange(ControlLayout.Default) }
            EditorAction("Done", onClick = onDone)
        }
    }
}

@Composable
private fun EditorAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = Color.White,
        fontSize = LABEL_SIZE,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(BUTTON_BACKGROUND)
            .border(1.dp, BUTTON_BORDER, RoundedCornerShape(6.dp))
            .pointerInput(label) { detectTapGestures { onClick() } }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

private val BUTTON_BACKGROUND = Color(0x66000000)
private val BUTTON_BORDER = Color(0x99FFFFFF)
private val BUTTON_LATCHED = Color(0xCCFFFFFF)
private val STICK_BASE = Color(0x40000000)
private val STICK_KNOB = Color(0x99FFFFFF)
private val EDITOR_SCRIM = Color(0x66000000)
private val EDITOR_PANEL = Color(0xE6101014)
private val EDITOR_OUTLINE = Color(0x80FFFFFF)
private val EDITOR_SELECTED = Color(0xFF7FD4FF)

private val EDGE_PADDING = 20.dp
private val GAP = 8.dp
private val LABEL_SIZE = 13.sp
private val SLIDER_WIDTH = 220.dp

/** How far a finger may travel and still count as a tap rather than a look. */
private val TAP_SLOP = 12.dp

/** How long a still finger waits before it becomes a held left button, in milliseconds. */
private const val MINE_HOLD_MILLIS = 180L

private const val GRAB_POLL_MILLIS = 100L

/** Mouse counts per density-independent pixel of travel. */
private const val LOOK_SENSITIVITY = 1.4f

/** The knob's diameter as a fraction of the frame's, matching how Bedrock's two textures relate. */
private const val KNOB_FRACTION = 2.4f

private const val STICK_DEAD_ZONE = 0.28f
private const val STICK_AXIS_THRESHOLD = 0.38f
private const val STICK_SPRINT_THRESHOLD = 0.92f

private const val HIDDEN_CONTROL_ALPHA = 0.3f
private const val DEFAULT_OPACITY = 0.75f
