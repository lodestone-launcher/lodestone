package com.github.lodestone.compat.lwjgl2;

import static org.lwjgl.glfw.GLFW.*;

import org.lwjgl.input.Keyboard;

/**
 * Translates between GLFW key codes and the ones LWJGL 2 hands to the game.
 *
 * <p>The two numbering schemes are unrelated. LWJGL 2 reports DirectInput / PS-2 set-1 scancodes,
 * where a key's number reflects its position on the original IBM keyboard matrix ({@code W} is 17
 * because it is the second key on the top letter row); GLFW numbers printable keys by their ASCII
 * value and everything else from 256 up. Neither can be derived from the other, so the table is
 * written out.
 *
 * <p>Getting this exactly right rather than approximately matters twice over: Minecraft writes
 * keybindings to {@code options.txt} as raw integers, so a world imported from a desktop install
 * keeps working only if our numbers are the genuine ones; and the controls screen displays
 * {@link Keyboard#getKeyName}, which is derived from the same constants.
 *
 * <p>Translating here rather than in the native shim is deliberate. Lodestone's on-screen controls
 * already speak GLFW, which is what every Minecraft version from 1.13 onwards wants, and this keeps
 * them speaking one language across the whole version range.
 */
public final class KeyCodes {

    /** GLFW's key codes are dense from 0 to {@code GLFW_KEY_LAST}, so a plain array is the map. */
    private static final int[] TO_LWJGL = new int[GLFW_KEY_LAST + 1];

    /** LWJGL 2's codes all fit below its own {@code Keyboard.KEYBOARD_SIZE}. */
    private static final int[] TO_GLFW = new int[Keyboard.KEYBOARD_SIZE];

    static {
        for (int i = 0; i < TO_GLFW.length; i++) {
            TO_GLFW[i] = GLFW_KEY_UNKNOWN;
        }

        map(GLFW_KEY_SPACE, Keyboard.KEY_SPACE);
        map(GLFW_KEY_APOSTROPHE, Keyboard.KEY_APOSTROPHE);
        map(GLFW_KEY_COMMA, Keyboard.KEY_COMMA);
        map(GLFW_KEY_MINUS, Keyboard.KEY_MINUS);
        map(GLFW_KEY_PERIOD, Keyboard.KEY_PERIOD);
        map(GLFW_KEY_SLASH, Keyboard.KEY_SLASH);

        map(GLFW_KEY_0, Keyboard.KEY_0);
        map(GLFW_KEY_1, Keyboard.KEY_1);
        map(GLFW_KEY_2, Keyboard.KEY_2);
        map(GLFW_KEY_3, Keyboard.KEY_3);
        map(GLFW_KEY_4, Keyboard.KEY_4);
        map(GLFW_KEY_5, Keyboard.KEY_5);
        map(GLFW_KEY_6, Keyboard.KEY_6);
        map(GLFW_KEY_7, Keyboard.KEY_7);
        map(GLFW_KEY_8, Keyboard.KEY_8);
        map(GLFW_KEY_9, Keyboard.KEY_9);

        map(GLFW_KEY_SEMICOLON, Keyboard.KEY_SEMICOLON);
        map(GLFW_KEY_EQUAL, Keyboard.KEY_EQUALS);

        map(GLFW_KEY_A, Keyboard.KEY_A);
        map(GLFW_KEY_B, Keyboard.KEY_B);
        map(GLFW_KEY_C, Keyboard.KEY_C);
        map(GLFW_KEY_D, Keyboard.KEY_D);
        map(GLFW_KEY_E, Keyboard.KEY_E);
        map(GLFW_KEY_F, Keyboard.KEY_F);
        map(GLFW_KEY_G, Keyboard.KEY_G);
        map(GLFW_KEY_H, Keyboard.KEY_H);
        map(GLFW_KEY_I, Keyboard.KEY_I);
        map(GLFW_KEY_J, Keyboard.KEY_J);
        map(GLFW_KEY_K, Keyboard.KEY_K);
        map(GLFW_KEY_L, Keyboard.KEY_L);
        map(GLFW_KEY_M, Keyboard.KEY_M);
        map(GLFW_KEY_N, Keyboard.KEY_N);
        map(GLFW_KEY_O, Keyboard.KEY_O);
        map(GLFW_KEY_P, Keyboard.KEY_P);
        map(GLFW_KEY_Q, Keyboard.KEY_Q);
        map(GLFW_KEY_R, Keyboard.KEY_R);
        map(GLFW_KEY_S, Keyboard.KEY_S);
        map(GLFW_KEY_T, Keyboard.KEY_T);
        map(GLFW_KEY_U, Keyboard.KEY_U);
        map(GLFW_KEY_V, Keyboard.KEY_V);
        map(GLFW_KEY_W, Keyboard.KEY_W);
        map(GLFW_KEY_X, Keyboard.KEY_X);
        map(GLFW_KEY_Y, Keyboard.KEY_Y);
        map(GLFW_KEY_Z, Keyboard.KEY_Z);

        map(GLFW_KEY_LEFT_BRACKET, Keyboard.KEY_LBRACKET);
        map(GLFW_KEY_BACKSLASH, Keyboard.KEY_BACKSLASH);
        map(GLFW_KEY_RIGHT_BRACKET, Keyboard.KEY_RBRACKET);
        map(GLFW_KEY_GRAVE_ACCENT, Keyboard.KEY_GRAVE);

        map(GLFW_KEY_ESCAPE, Keyboard.KEY_ESCAPE);
        map(GLFW_KEY_ENTER, Keyboard.KEY_RETURN);
        map(GLFW_KEY_TAB, Keyboard.KEY_TAB);
        map(GLFW_KEY_BACKSPACE, Keyboard.KEY_BACK);
        map(GLFW_KEY_INSERT, Keyboard.KEY_INSERT);
        map(GLFW_KEY_DELETE, Keyboard.KEY_DELETE);
        map(GLFW_KEY_RIGHT, Keyboard.KEY_RIGHT);
        map(GLFW_KEY_LEFT, Keyboard.KEY_LEFT);
        map(GLFW_KEY_DOWN, Keyboard.KEY_DOWN);
        map(GLFW_KEY_UP, Keyboard.KEY_UP);
        map(GLFW_KEY_PAGE_UP, Keyboard.KEY_PRIOR);
        map(GLFW_KEY_PAGE_DOWN, Keyboard.KEY_NEXT);
        map(GLFW_KEY_HOME, Keyboard.KEY_HOME);
        map(GLFW_KEY_END, Keyboard.KEY_END);

        map(GLFW_KEY_CAPS_LOCK, Keyboard.KEY_CAPITAL);
        map(GLFW_KEY_SCROLL_LOCK, Keyboard.KEY_SCROLL);
        map(GLFW_KEY_NUM_LOCK, Keyboard.KEY_NUMLOCK);
        map(GLFW_KEY_PRINT_SCREEN, Keyboard.KEY_SYSRQ);
        map(GLFW_KEY_PAUSE, Keyboard.KEY_PAUSE);

        map(GLFW_KEY_F1, Keyboard.KEY_F1);
        map(GLFW_KEY_F2, Keyboard.KEY_F2);
        map(GLFW_KEY_F3, Keyboard.KEY_F3);
        map(GLFW_KEY_F4, Keyboard.KEY_F4);
        map(GLFW_KEY_F5, Keyboard.KEY_F5);
        map(GLFW_KEY_F6, Keyboard.KEY_F6);
        map(GLFW_KEY_F7, Keyboard.KEY_F7);
        map(GLFW_KEY_F8, Keyboard.KEY_F8);
        map(GLFW_KEY_F9, Keyboard.KEY_F9);
        map(GLFW_KEY_F10, Keyboard.KEY_F10);
        map(GLFW_KEY_F11, Keyboard.KEY_F11);
        map(GLFW_KEY_F12, Keyboard.KEY_F12);
        map(GLFW_KEY_F13, Keyboard.KEY_F13);
        map(GLFW_KEY_F14, Keyboard.KEY_F14);
        map(GLFW_KEY_F15, Keyboard.KEY_F15);
        map(GLFW_KEY_F16, Keyboard.KEY_F16);
        map(GLFW_KEY_F17, Keyboard.KEY_F17);
        map(GLFW_KEY_F18, Keyboard.KEY_F18);
        map(GLFW_KEY_F19, Keyboard.KEY_F19);

        map(GLFW_KEY_KP_0, Keyboard.KEY_NUMPAD0);
        map(GLFW_KEY_KP_1, Keyboard.KEY_NUMPAD1);
        map(GLFW_KEY_KP_2, Keyboard.KEY_NUMPAD2);
        map(GLFW_KEY_KP_3, Keyboard.KEY_NUMPAD3);
        map(GLFW_KEY_KP_4, Keyboard.KEY_NUMPAD4);
        map(GLFW_KEY_KP_5, Keyboard.KEY_NUMPAD5);
        map(GLFW_KEY_KP_6, Keyboard.KEY_NUMPAD6);
        map(GLFW_KEY_KP_7, Keyboard.KEY_NUMPAD7);
        map(GLFW_KEY_KP_8, Keyboard.KEY_NUMPAD8);
        map(GLFW_KEY_KP_9, Keyboard.KEY_NUMPAD9);
        map(GLFW_KEY_KP_DECIMAL, Keyboard.KEY_DECIMAL);
        map(GLFW_KEY_KP_DIVIDE, Keyboard.KEY_DIVIDE);
        map(GLFW_KEY_KP_MULTIPLY, Keyboard.KEY_MULTIPLY);
        map(GLFW_KEY_KP_SUBTRACT, Keyboard.KEY_SUBTRACT);
        map(GLFW_KEY_KP_ADD, Keyboard.KEY_ADD);
        map(GLFW_KEY_KP_ENTER, Keyboard.KEY_NUMPADENTER);
        map(GLFW_KEY_KP_EQUAL, Keyboard.KEY_NUMPADEQUALS);

        map(GLFW_KEY_LEFT_SHIFT, Keyboard.KEY_LSHIFT);
        map(GLFW_KEY_LEFT_CONTROL, Keyboard.KEY_LCONTROL);
        map(GLFW_KEY_LEFT_ALT, Keyboard.KEY_LMENU);
        map(GLFW_KEY_LEFT_SUPER, Keyboard.KEY_LMETA);
        map(GLFW_KEY_RIGHT_SHIFT, Keyboard.KEY_RSHIFT);
        map(GLFW_KEY_RIGHT_CONTROL, Keyboard.KEY_RCONTROL);
        map(GLFW_KEY_RIGHT_ALT, Keyboard.KEY_RMENU);
        map(GLFW_KEY_RIGHT_SUPER, Keyboard.KEY_RMETA);
        map(GLFW_KEY_MENU, Keyboard.KEY_APPS);

        // Deliberately unmapped, in both directions:
        //
        // GLFW_KEY_WORLD_1/2 are the extra keys on non-US layouts, which DirectInput numbers by
        // physical position rather than giving them codes of their own. GLFW_KEY_F20 to F25 have no
        // DirectInput scancode at all.
        //
        // On the LWJGL 2 side the leftovers are the Japanese and OEM keys (KANA, CONVERT, KANJI,
        // YEN, AT, COLON, CIRCUMFLEX, UNDERLINE, AX, STOP, UNLABELED, SECTION, NUMPADCOMMA,
        // FUNCTION, CLEAR, POWER, SLEEP), none of which GLFW reports separately.
        //
        // A key with no counterpart still reaches text fields: the character callback fires
        // regardless, and an unmatched character becomes a KEY_NONE event carrying the codepoint.
    }

    private KeyCodes() {
    }

    private static void map(int glfwKey, int lwjglKey) {
        TO_LWJGL[glfwKey] = lwjglKey;
        TO_GLFW[lwjglKey] = glfwKey;
    }

    /**
     * The LWJGL 2 code for {@code glfwKey}, or {@code Keyboard.KEY_NONE} if it has no counterpart.
     */
    public static int toLwjgl(int glfwKey) {
        if (glfwKey < 0 || glfwKey >= TO_LWJGL.length) {
            return Keyboard.KEY_NONE;
        }
        return TO_LWJGL[glfwKey];
    }

    /** The GLFW code for {@code lwjglKey}, or {@code GLFW_KEY_UNKNOWN} if it has no counterpart. */
    public static int toGlfw(int lwjglKey) {
        if (lwjglKey <= 0 || lwjglKey >= TO_GLFW.length) {
            return GLFW_KEY_UNKNOWN;
        }
        return TO_GLFW[lwjglKey];
    }
}
