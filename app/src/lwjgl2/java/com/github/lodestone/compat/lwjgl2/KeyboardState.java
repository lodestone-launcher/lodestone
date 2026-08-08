package com.github.lodestone.compat.lwjgl2;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

import org.lwjgl.input.Keyboard;

/**
 * Turns GLFW's key and character callbacks into the event queue LWJGL 2's {@code Keyboard} reads.
 *
 * <p>The shapes do not line up. LWJGL 2 is a cursor over a queue — {@code next()} advances and the
 * getters read the record it landed on — while GLFW pushes callbacks during {@code glfwPollEvents}.
 * Worse, one LWJGL 2 event is two GLFW callbacks: pressing {@code W} fires the key callback and
 * then the character callback, and LWJGL 2 reports both in a single record with
 * {@code getEventKey()} and {@code getEventCharacter()}.
 *
 * <p>So a key press is held back until the poll can say whether a character followed it. Everything
 * chat and text fields do depends on the two arriving together, and on an unmatched character —
 * from an IME or a dead key — still reaching the game as a record with no key at all.
 */
public final class KeyboardState {

    /** LWJGL 2's own keyboard queue holds this many events and drops the rest. */
    private static final int QUEUE_SIZE = 50;

    // Parallel arrays rather than a record type: this queue is written on every keystroke of every
    // frame, and an object per event would be garbage the game never asked for.
    private static final int[] queuedKey = new int[QUEUE_SIZE];
    private static final char[] queuedCharacter = new char[QUEUE_SIZE];
    private static final boolean[] queuedState = new boolean[QUEUE_SIZE];
    private static final boolean[] queuedRepeat = new boolean[QUEUE_SIZE];
    private static final long[] queuedNanos = new long[QUEUE_SIZE];

    private static int head;
    private static int count;

    private static int eventKey;
    private static char eventCharacter;
    private static boolean eventState;
    private static boolean eventRepeat;
    private static long eventNanos;

    private static boolean repeatEnabled;

    // A press waiting to see whether a character callback completes it.
    private static boolean pendingPress;
    private static int pendingKey;
    private static boolean pendingRepeat;
    private static long pendingNanos;

    /** Set when a repeat was dropped, so the character GLFW fires alongside it goes too. */
    private static boolean dropNextCharacter;

    private KeyboardState() {
    }

    public static void reset() {
        head = 0;
        count = 0;
        pendingPress = false;
        dropNextCharacter = false;
        eventKey = Keyboard.KEY_NONE;
        eventCharacter = (char) Keyboard.CHAR_NONE;
        eventState = false;
        eventRepeat = false;
        eventNanos = 0L;
    }

    public static void setRepeatEnabled(boolean enabled) {
        repeatEnabled = enabled;
    }

    public static boolean isRepeatEnabled() {
        return repeatEnabled;
    }

    static void onKey(int glfwKey, int action) {
        // Whatever was pending cannot gain a character any more: GLFW fires the character callback
        // immediately after the key that produced it, never after an intervening key.
        flushPending();

        int key = KeyCodes.toLwjgl(glfwKey);
        long nanos = System.nanoTime();

        if (action == GLFW_RELEASE) {
            dropNextCharacter = false;
            push(key, (char) Keyboard.CHAR_NONE, false, false, nanos);
            return;
        }

        boolean repeat = action == GLFW_REPEAT;
        if (repeat && !repeatEnabled) {
            // Minecraft only enables repeats while a text field has focus. With them off the game
            // must see nothing at all, so the character GLFW is about to report goes as well.
            dropNextCharacter = true;
            return;
        }

        dropNextCharacter = false;
        pendingPress = true;
        pendingKey = key;
        pendingRepeat = repeat;
        pendingNanos = nanos;
    }

    static void onChar(int codepoint) {
        if (dropNextCharacter) {
            dropNextCharacter = false;
            return;
        }
        if (codepoint > Character.MAX_VALUE) {
            // LWJGL 2's event character is a single `char`, so anything outside the basic plane is
            // reported the way the game would read it from a string: as its two surrogates.
            char[] pair = Character.toChars(codepoint);
            pushCharacter(pair[0]);
            pushCharacter(pair[1]);
            return;
        }
        pushCharacter((char) codepoint);
    }

    /**
     * Emits any key press that never got a character.
     *
     * <p>Called once the poll is over, because until then the character callback might still
     * arrive.
     */
    static void endPoll() {
        flushPending();
    }

    public static boolean next() {
        if (count == 0) {
            return false;
        }
        eventKey = queuedKey[head];
        eventCharacter = queuedCharacter[head];
        eventState = queuedState[head];
        eventRepeat = queuedRepeat[head];
        eventNanos = queuedNanos[head];
        head = (head + 1) % QUEUE_SIZE;
        count--;
        return true;
    }

    public static int eventKey() {
        return eventKey;
    }

    public static char eventCharacter() {
        return eventCharacter;
    }

    public static boolean eventState() {
        return eventState;
    }

    public static boolean isRepeatEvent() {
        return eventRepeat;
    }

    public static long eventNanos() {
        return eventNanos;
    }

    public static int queuedEvents() {
        return count;
    }

    /**
     * Whether a key is held right now.
     *
     * <p>Asks GLFW rather than tracking it from the events, because the native shim keeps the
     * authoritative table and answering from a queue that can drop events would leave a key stuck
     * down for as long as the game is running.
     */
    public static boolean isKeyDown(int lwjglKey) {
        long window = Window.handle();
        if (window == NULL) {
            return false;
        }
        int glfwKey = KeyCodes.toGlfw(lwjglKey);
        if (glfwKey == GLFW_KEY_UNKNOWN) {
            return false;
        }
        return glfwGetKey(window, glfwKey) == GLFW_PRESS;
    }

    private static void pushCharacter(char character) {
        if (pendingPress) {
            push(pendingKey, character, true, pendingRepeat, pendingNanos);
            pendingPress = false;
            return;
        }
        // No key to attach it to: a dead key, an IME commit, or the second character of a key that
        // produced more than one. LWJGL 2 reports these with no key code, and text fields read the
        // character regardless of what `getEventKey()` says.
        push(Keyboard.KEY_NONE, character, true, false, System.nanoTime());
    }

    private static void flushPending() {
        if (!pendingPress) {
            return;
        }
        pendingPress = false;
        push(pendingKey, (char) Keyboard.CHAR_NONE, true, pendingRepeat, pendingNanos);
    }

    private static void push(int key, char character, boolean state, boolean repeat, long nanos) {
        if (count == QUEUE_SIZE) {
            // Full. LWJGL 2 drops the excess rather than growing, so that a game which stops
            // polling — loading a world, or hung — cannot make the queue a leak.
            head = (head + 1) % QUEUE_SIZE;
            count--;
        }
        int tail = (head + count) % QUEUE_SIZE;
        queuedKey[tail] = key;
        queuedCharacter[tail] = character;
        queuedState[tail] = state;
        queuedRepeat[tail] = repeat;
        queuedNanos[tail] = nanos;
        count++;
    }
}
