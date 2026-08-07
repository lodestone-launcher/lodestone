package com.github.lodestone.ui.game

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.github.lodestone.domain.model.launch.LaunchRequest
import com.github.lodestone.runtime.GlfwBridge
import com.github.lodestone.runtime.JvmBridge
import timber.log.Timber
import java.io.File

/**
 * Hosts the running game: a full-screen surface with the touch controls drawn over it.
 *
 * This activity runs in its own `:game` process. That is not a nicety — the hosted JVM installs its
 * own signal handlers and terminates the process outright when the game calls `System.exit`, so
 * sharing a process with the launcher UI would take the whole app down every time the player quits.
 */
class GameActivity : ComponentActivity() {

    private var surfaceView: SurfaceView? = null
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The game draws every pixel itself, including behind the cutout, and must never be
        // interrupted by the screen turning off mid-session.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        enterImmersiveMode()

        setContent {
            GameScreen(
                onSurfaceCreated = { view -> surfaceView = view },
                onOpenMenu = ::showInGameMenu,
                onSurfaceReady = ::startGame,
            )
        }
    }

    /**
     * Boots the VM once, after the surface exists.
     *
     * Order matters. The stdio pump has to be installed before the VM starts, because HotSpot
     * captures the descriptors as it comes up and anything it writes beforehand is lost. The
     * translation layer has to be opened here too, on this thread: gl4es probes the driver from an
     * ELF constructor and leaves no EGL context current on whichever thread ran it, so if LWJGL is
     * the one to open it the game loses the context it has just made current and dies building its
     * first framebuffer. The launch itself blocks until the game exits, so it runs on a thread
     * sized for HotSpot's primordial stack rather than on the main thread.
     */
    @Synchronized
    private fun startGame() {
        if (started) {
            return
        }
        val requestFile = File(filesDir, LAUNCH_REQUEST_FILE)
        val request = LaunchRequest.readFrom(requestFile)
        if (request == null) {
            Timber.e("No launch request at %s", requestFile)
            finish()
            return
        }
        started = true

        // Set before the pump starts, since the pump reads it when installing the redirect.
        JvmBridge.setEnv("LODESTONE_STDIO_LOG", File(filesDir, "jvm_stdio.log").absolutePath)
        JvmBridge.startStdioPump { line -> Timber.tag("Minecraft").i(line) }
        request.environment.forEach { (key, value) -> JvmBridge.setEnv(key, value) }
        request.translationLayerPath?.let { path ->
            val loaded = GlfwBridge.loadTranslationLayer(path, request.eglLibraryPath)
            Timber.i("Translation layer %s loaded: %b", path, loaded)
        }

        Timber.i("Launching %s via %s", request.versionId, request.libjvmPath)
        JvmBridge.runGameThread {
            val status = JvmBridge.launch(
                libjvm = File(request.libjvmPath),
                workingDirectory = File(request.gameDirectory),
                jvmArgs = request.jvmArgs,
                mainClass = request.mainClass,
                gameArgs = request.gameArgs,
            )
            Timber.i("Game exited with %d", status)
        }
    }

    /**
     * Hides the status and navigation bars and keeps them hidden.
     *
     * `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` matters here: without it the first swipe near an edge
     * — which in Minecraft is an ordinary look gesture — would permanently bring the system bars
     * back and shrink the viewport.
     */
    private fun enterImmersiveMode() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // Android restores the system bars after a dialog or a notification shade pull.
            enterImmersiveMode()
        }
    }

    private fun showInGameMenu() {
        // Routed through the game rather than handled here, so the player lands on Minecraft's own
        // pause screen and the world is saved the way the game expects.
        GlfwBridge.sendKey(0, 0, GlfwBridge.Action.PRESS)
    }

    companion object {
        const val LAUNCH_REQUEST_FILE = "launch_request.json"
    }

    override fun onDestroy() {
        super.onDestroy()
        surfaceView = null
    }
}

@Composable
private fun GameScreen(
    onSurfaceCreated: (SurfaceView) -> Unit,
    onOpenMenu: () -> Unit,
    onSurfaceReady: () -> Unit = {},
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                SurfaceView(context).also { view ->
                    view.holder.addCallback(GameSurfaceCallback(onSurfaceReady))
                    onSurfaceCreated(view)
                }
            },
        )

        TouchControls(
            modifier = Modifier.fillMaxSize(),
            onOpenMenu = onOpenMenu,
        )
    }
}

/**
 * Feeds the rendering surface to the GLFW shim.
 *
 * The game's render thread outlives any individual surface — Android destroys and recreates it when
 * the activity is backgrounded — so the shim swaps the EGL window surface underneath the live
 * context rather than tearing the context down and losing every GL object with it.
 */
private class GameSurfaceCallback(private val onSurfaceReady: () -> Unit) : SurfaceHolder.Callback {

    @SuppressLint("UnsafeOptInUsageError")
    override fun surfaceCreated(holder: SurfaceHolder) {
        Timber.i("Game surface created")
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Timber.i("Game surface: %dx%d", width, height)
        GlfwBridge.setSurface(holder.surface, width, height)
        onSurfaceReady()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Timber.i("Game surface destroyed")
        // Passing null parks the render thread on the next swap instead of letting it draw into a
        // surface the compositor has already reclaimed.
        GlfwBridge.setSurface(null, 0, 0)
    }
}
