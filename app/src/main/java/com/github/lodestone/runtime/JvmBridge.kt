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
     * The process changes directory to [workingDirectory] first, which is the whole reason this
     * takes it: the game directory is where Minecraft expects to find and create everything it
     * addresses by a relative path.
     *
     * Call this from the thread created by [runGameThread], never from an ordinary one.
     */
    fun launch(
        libjvm: File,
        workingDirectory: File,
        jvmArgs: List<String>,
        mainClass: String,
        gameArgs: List<String>,
    ): Int {
        ensureLoaded()
        return nativeLaunch(
            libjvm.absolutePath,
            workingDirectory.absolutePath,
            toInitOptions(jvmArgs).toTypedArray(),
            mainClass,
            gameArgs.toTypedArray(),
        )
    }

    /**
     * Rewrites `java`-launcher syntax into what `JNI_CreateJavaVM` accepts.
     *
     * The two are not the same language. Version manifests are written for the command line, where
     * `-cp <path>` is two arguments; the VM's own option table has no `-cp` at all and takes the
     * classpath as a single `-Djava.class.path=` property. Passing the manifest form through
     * verbatim makes the VM reject it and refuse to start.
     */
    internal fun toInitOptions(jvmArgs: List<String>): List<String> {
        val options = mutableListOf<String>()
        var index = 0
        while (index < jvmArgs.size) {
            val argument = jvmArgs[index]
            when {
                (argument == "-cp" || argument == "-classpath") && index + 1 < jvmArgs.size -> {
                    options += "-Djava.class.path=${jvmArgs[index + 1]}"
                    index += 2
                }

                argument.startsWith("-cp=") ->
                    options += "-Djava.class.path=${argument.removePrefix("-cp=")}".also { index++ }

                else -> {
                    options += argument
                    index++
                }
            }
        }
        return options
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
        workingDirectory: String,
        jvmArgs: Array<String>,
        mainClass: String,
        gameArgs: Array<String>,
    ): Int
}
