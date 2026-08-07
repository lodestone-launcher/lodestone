package com.github.lodestone.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.lodestone.runtime.GlfwBridge
import com.github.lodestone.runtime.GlfwKeys

/**
 * The on-screen controls drawn over the game.
 *
 * Every button maps to a GLFW key or mouse event, so the game sees exactly what a desktop player's
 * keyboard would send and no Minecraft-side changes are needed. Buttons are hold-to-press rather
 * than toggle, matching how the keys they stand in for behave.
 */
@Composable
fun TouchControls(
    modifier: Modifier = Modifier,
    opacity: Float = DEFAULT_OPACITY,
    buttonSize: Dp = DEFAULT_BUTTON_SIZE,
    onOpenMenu: () -> Unit = {},
) {
    Box(modifier = modifier.fillMaxSize()) {
        // The look area sits underneath the buttons so a drag that starts on a button never turns
        // the camera as well.
        LookArea(modifier = Modifier.fillMaxSize())

        MovementPad(
            buttonSize = buttonSize,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(EDGE_PADDING)
                .alpha(opacity),
        )

        ActionButtons(
            buttonSize = buttonSize,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(EDGE_PADDING)
                .alpha(opacity),
        )

        SystemButtons(
            buttonSize = buttonSize,
            onOpenMenu = onOpenMenu,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(EDGE_PADDING)
                .alpha(opacity),
        )
    }
}

/**
 * Turns drags into camera movement and taps into clicks.
 *
 * Which of those applies depends on whether the game has the pointer grabbed: in a menu the game
 * wants absolute positions and clicks, and in the world it wants relative movement.
 */
@Composable
private fun LookArea(modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    // Touch travel is in pixels but the game expects mouse counts; without scaling, a swipe on a
    // high-density panel would spin the camera several times round.
    val sensitivity = remember(density) { 1f / density.density }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    if (GlfwBridge.isCursorGrabbed()) {
                        GlfwBridge.sendCursorDelta(
                            dragAmount.x * sensitivity,
                            dragAmount.y * sensitivity,
                        )
                    } else {
                        GlfwBridge.sendCursorPos(change.position.x, change.position.y)
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { offset ->
                        if (!GlfwBridge.isCursorGrabbed()) {
                            GlfwBridge.sendCursorPos(offset.x, offset.y)
                        }
                        GlfwBridge.sendMouseButton(
                            GlfwBridge.MouseButton.LEFT,
                            GlfwBridge.Action.PRESS,
                        )
                        // Waiting for the release keeps a held tap mining rather than registering
                        // as a single instantaneous click.
                        tryAwaitRelease()
                        GlfwBridge.sendMouseButton(
                            GlfwBridge.MouseButton.LEFT,
                            GlfwBridge.Action.RELEASE,
                        )
                    },
                    onLongPress = {
                        GlfwBridge.sendMouseButton(
                            GlfwBridge.MouseButton.RIGHT,
                            GlfwBridge.Action.PRESS,
                        )
                        GlfwBridge.sendMouseButton(
                            GlfwBridge.MouseButton.RIGHT,
                            GlfwBridge.Action.RELEASE,
                        )
                    },
                )
            },
    )
}

@Composable
private fun MovementPad(buttonSize: Dp, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(GAP),
    ) {
        ControlButton("W", GlfwKeys.W, buttonSize)
        Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
            ControlButton("A", GlfwKeys.A, buttonSize)
            ControlButton("S", GlfwKeys.S, buttonSize)
            ControlButton("D", GlfwKeys.D, buttonSize)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
            ControlButton("Jump", GlfwKeys.SPACE, buttonSize)
            ControlButton("Sneak", GlfwKeys.LEFT_SHIFT, buttonSize)
        }
    }
}

@Composable
private fun ActionButtons(buttonSize: Dp, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(GAP),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
            MouseButtonControl("Use", GlfwBridge.MouseButton.RIGHT, buttonSize)
            MouseButtonControl("Hit", GlfwBridge.MouseButton.LEFT, buttonSize)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
            ControlButton("Inv", GlfwKeys.E, buttonSize)
            ControlButton("Drop", GlfwKeys.Q, buttonSize)
            ControlButton("Chat", GlfwKeys.T, buttonSize)
        }
    }
}

@Composable
private fun SystemButtons(
    buttonSize: Dp,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(GAP)) {
        ControlButton("F3", GlfwKeys.F3, buttonSize)
        ControlButton("Esc", GlfwKeys.ESCAPE, buttonSize)
        Box(
            modifier = Modifier
                .size(buttonSize)
                .background(BUTTON_BACKGROUND)
                .border(1.dp, BUTTON_BORDER)
                .pointerInput(Unit) { detectTapGestures { onOpenMenu() } },
            contentAlignment = Alignment.Center,
        ) {
            Text("☰", color = Color.White, fontSize = LABEL_SIZE)
        }
    }
}

/** A button that holds a GLFW key down for as long as it is touched. */
@Composable
private fun ControlButton(label: String, key: Int, size: Dp) {
    HoldButton(
        label = label,
        size = size,
        onPress = { GlfwBridge.sendKey(key, 0, GlfwBridge.Action.PRESS) },
        onRelease = { GlfwBridge.sendKey(key, 0, GlfwBridge.Action.RELEASE) },
    )
}

@Composable
private fun MouseButtonControl(label: String, button: Int, size: Dp) {
    HoldButton(
        label = label,
        size = size,
        onPress = { GlfwBridge.sendMouseButton(button, GlfwBridge.Action.PRESS) },
        onRelease = { GlfwBridge.sendMouseButton(button, GlfwBridge.Action.RELEASE) },
    )
}

@Composable
private fun HoldButton(
    label: String,
    size: Dp,
    onPress: () -> Unit,
    onRelease: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .background(BUTTON_BACKGROUND)
            .border(1.dp, BUTTON_BORDER)
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
private val EDGE_PADDING = 16.dp
private val GAP = 8.dp
private val LABEL_SIZE = 13.sp

private const val DEFAULT_OPACITY = 0.7f
private val DEFAULT_BUTTON_SIZE = 52.dp
