package org.lwjgl;

/**
 * LWJGL 2's odds and ends: the clock, the version, and opening a link.
 *
 * <p>Minecraft reads wall time as {@code Sys.getTime() * 1000L / Sys.getTimerResolution()}, so the
 * two have to be chosen together. Milliseconds against a resolution of 1000 makes that expression
 * exact and keeps the multiplication far away from overflow, which a nanosecond clock would not: a
 * nanosecond-resolution {@code getTime()} would overflow a {@code long} after a couple of hours of
 * uptime once multiplied by 1000. This is also what LWJGL 2 itself reported on Linux.
 */
public final class Sys {

    private static final String VERSION = "2.9.4";

    private Sys() {
    }

    public static String getVersion() {
        return VERSION;
    }

    public static void initialize() {
    }

    public static boolean is64Bit() {
        return true;
    }

    /** Milliseconds, which is what {@link #getTime()} counts in. */
    public static long getTimerResolution() {
        return 1000L;
    }

    /**
     * A monotonic count of milliseconds.
     *
     * <p>{@code System.nanoTime()} rather than {@code currentTimeMillis()}, because the game
     * measures intervals with this and a clock that can be stepped by NTP or by the user would make
     * ticks vanish or arrive in bursts.
     */
    public static long getTime() {
        return System.nanoTime() / 1_000_000L;
    }

    /**
     * Opening a link needs an Android {@code Intent}, which nothing on the game's side of the JVM
     * can reach yet. Reporting failure is what LWJGL 2 does when it has no browser to hand, and the
     * callers already show the URL as text instead.
     */
    public static boolean openURL(String url) {
        return false;
    }

    public static void alert(String title, String message) {
    }

    public static String getClipboard() {
        return null;
    }
}
