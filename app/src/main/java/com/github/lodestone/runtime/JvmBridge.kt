package com.github.lodestone.runtime

import java.io.File

/**
 * Thin Kotlin face on `liblodestone_jvm.so`, which boots a HotSpot VM inside this process.
 *
 * Everything here is process-global by nature: there is exactly one set of standard file
 * descriptors to redirect and exactly one VM per process. The game therefore runs in its own
 * `:game` process, so that a crash — or a `System.exit` from the game, which terminates the process
 * outright — leaves the launcher UI untouched.
 */
object JvmBridge {

    /** Receives the game's stdout and stderr one line at a time, from the pump thread. */
    fun interface OutputSink {
        fun accept(line: String)
    }

    /**
     * HotSpot adopts the calling thread as its primordial thread. The interpreter, the JIT's
     * deoptimisation paths and deeply recursive class initialisation all run on that stack, and
     * Android's 1 MiB default overflows long before Minecraft finishes booting.
     */
    private const val GAME_THREAD_STACK_BYTES = 32L * 1024 * 1024

    @Volatile
    private var loaded = false

    @Synchronized
    private fun ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("lodestone_jvm")
            loaded = true
        }
    }

    /**
     * Redirects stdout and stderr to [sink]. Must be called before [launch], because the VM
     * captures the descriptors when it starts. Subsequent calls are ignored.
     */
    fun startStdioPump(sink: OutputSink): Boolean {
        ensureLoaded()
        return nativeStartStdioPump(sink)
    }

    /** Sets a process environment variable that the hosted VM and its native libraries will see. */
    fun setEnv(name: String, value: String) {
        ensureLoaded()
        nativeSetEnv(name, value)
    }

    /**
     * Starts the VM at [libjvm] and runs `[mainClass].main([gameArgs])`, blocking until the game
     * exits. Returns 0 on a clean return, 1 if main threw, and a negative code if the VM or the
     * main class could not be brought up.
     *
     * Call this from the thread created by [runGameThread], never from an ordinary one.
     */
    fun launch(
        libjvm: File,
        jvmArgs: List<String>,
        mainClass: String,
        gameArgs: List<String>,
    ): Int {
        ensureLoaded()
        return nativeLaunch(
            libjvm.absolutePath,
            jvmArgs.toTypedArray(),
            mainClass,
            gameArgs.toTypedArray(),
        )
    }

    /** Runs [body] on a thread sized for HotSpot's primordial thread. */
    fun runGameThread(body: () -> Unit): Thread =
        Thread(null, body, "lodestone-game", GAME_THREAD_STACK_BYTES).apply {
            isDaemon = false
            start()
        }

    @JvmStatic
    private external fun nativeStartStdioPump(sink: OutputSink): Boolean

    @JvmStatic
    private external fun nativeSetEnv(name: String, value: String)

    @JvmStatic
    private external fun nativeLaunch(
        libjvmPath: String,
        jvmArgs: Array<String>,
        mainClass: String,
        gameArgs: Array<String>,
    ): Int
}
