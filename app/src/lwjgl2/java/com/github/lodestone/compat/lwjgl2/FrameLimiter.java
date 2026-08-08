package com.github.lodestone.compat.lwjgl2;

/**
 * LWJGL 2's {@code Display.sync(fps)}, rebuilt.
 *
 * <p>Deliberately not {@code glfwSwapInterval}. Minecraft's video settings drive the frame cap and
 * vsync as two independent options — the frame-rate slider calls {@code sync()} and the "Use VSync"
 * toggle calls {@code setVSyncEnabled()} — and folding one into the other would make either setting
 * move the other.
 *
 * <p>The wait is split because neither half works alone: {@code Thread.sleep} on Android can
 * overshoot by several milliseconds, which at 60 fps is a visible stutter, while spinning for the
 * whole frame would burn a core and cook the phone. So it sleeps until the last millisecond and
 * spins the rest.
 */
public final class FrameLimiter {

    /**
     * How much of the wait is spun rather than slept, since {@code Thread.sleep} cannot be trusted
     * finer.
     */
    private static final long SPIN_NANOS = 1_000_000L;

    /** When the current frame is allowed to end, or 0 before the first call. */
    private static long deadline;

    private FrameLimiter() {
    }

    public static void sync(int fps) {
        if (fps <= 0) {
            deadline = 0L;
            return;
        }

        long period = 1_000_000_000L / fps;
        long now = System.nanoTime();
        if (deadline == 0L || now - deadline > period) {
            // Either the first frame, or the game stalled — loading a world, or swapping into the
            // background. Catching the lost time up would run the next frames back to back at
            // whatever speed the CPU allows, so the cadence restarts from here instead.
            deadline = now;
        }
        deadline += period;

        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                return;
            }
            try {
                long sleepMillis = (remaining - SPIN_NANOS) / 1_000_000L;
                if (sleepMillis > 0L) {
                    Thread.sleep(sleepMillis);
                } else {
                    Thread.yield();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public static void reset() {
        deadline = 0L;
    }
}
