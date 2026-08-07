#pragma once

#include <EGL/egl.h>
#include <android/native_window.h>

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <mutex>

// The subset of GLFW 3.4's public constants that Minecraft and LWJGL actually reference. Values
// must match upstream exactly: LWJGL passes them straight through from Java.
#define GLFW_FALSE 0
#define GLFW_TRUE 1
#define GLFW_RELEASE 0
#define GLFW_PRESS 1
#define GLFW_REPEAT 2

#define GLFW_CURSOR 0x00033001
#define GLFW_STICKY_KEYS 0x00033002
#define GLFW_STICKY_MOUSE_BUTTONS 0x00033003
#define GLFW_RAW_MOUSE_MOTION 0x00033005

#define GLFW_CURSOR_NORMAL 0x00034001
#define GLFW_CURSOR_HIDDEN 0x00034002
#define GLFW_CURSOR_DISABLED 0x00034003

#define GLFW_NOT_INITIALIZED 0x00010001
#define GLFW_PLATFORM_ERROR 0x00010008

#define GLFW_CLIENT_API 0x00022001
#define GLFW_CONTEXT_VERSION_MAJOR 0x00022002
#define GLFW_CONTEXT_VERSION_MINOR 0x00022003
#define GLFW_OPENGL_PROFILE 0x00022008
#define GLFW_OPENGL_API 0x00030001
#define GLFW_OPENGL_ES_API 0x00030002
#define GLFW_NO_API 0

#define GLFW_PLATFORM_WIN32 0x00060001
#define GLFW_PLATFORM_COCOA 0x00060002
#define GLFW_PLATFORM_WAYLAND 0x00060003
#define GLFW_PLATFORM_X11 0x00060004
#define GLFW_PLATFORM_NULL 0x00060005

namespace lodestone::glfw {

/**
 * One input event, queued by the Android UI thread and delivered on the game's main thread.
 *
 * GLFW guarantees callbacks run on whichever thread calls `glfwPollEvents`, and Minecraft relies on
 * that: its input handling touches game state that is not safe to reach from Android's UI thread.
 */
struct Event {
    enum class Type {
        Key,
        Char,
        MouseButton,
        CursorPos,
        Scroll,
        WindowSize,
        WindowFocus,
        WindowClose,
    };

    Type type;
    int a = 0;
    int b = 0;
    int c = 0;
    int d = 0;
    double x = 0;
    double y = 0;
};

/**
 * Everything the shim knows about "the window".
 *
 * Minecraft only ever creates one, so a single global instance stands in for GLFW's window objects
 * and the opaque `GLFWwindow*` handles it hands out are just this struct's address.
 */
struct WindowState {
    std::atomic_bool initialised{false};
    std::atomic_bool shouldClose{false};

    // --- Surface ownership -------------------------------------------------------------------
    // The Android UI thread publishes a surface here; the render thread picks it up on its next
    // swap. The two never touch EGL at the same time.
    std::mutex surfaceMutex;
    std::condition_variable surfaceChanged;
    ANativeWindow* pendingWindow = nullptr;
    bool surfaceDirty = false;

    // --- EGL, owned exclusively by the render thread -------------------------------------------
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLSurface surface = EGL_NO_SURFACE;
    /** Stands in for the window surface whenever Android has taken the real one away. */
    EGLSurface placeholder = EGL_NO_SURFACE;
    EGLContext context = EGL_NO_CONTEXT;
    EGLConfig config = nullptr;
    ANativeWindow* nativeWindow = nullptr;

    std::atomic_int width{1280};
    std::atomic_int height{720};
    std::atomic_int swapInterval{1};

    // --- Input ---------------------------------------------------------------------------------
    std::mutex eventMutex;
    std::deque<Event> events;

    std::atomic_int cursorMode{GLFW_CURSOR_NORMAL};
    // Guarded by eventMutex: the UI thread writes these, and glfwGetCursorPos reads them.
    double cursorX = 0;
    double cursorY = 0;

    // Key and button state, so glfwGetKey and glfwGetMouseButton can answer without a callback.
    std::atomic_uchar keys[512]{};
    std::atomic_uchar mouseButtons[8]{};

    // --- Callbacks, set from the game's main thread before the loop starts ----------------------
    void* keyCallback = nullptr;
    void* charCallback = nullptr;
    void* mouseButtonCallback = nullptr;
    void* cursorPosCallback = nullptr;
    void* scrollCallback = nullptr;
    void* windowSizeCallback = nullptr;
    void* framebufferSizeCallback = nullptr;
    void* windowFocusCallback = nullptr;
    void* windowCloseCallback = nullptr;
    void* errorCallback = nullptr;
};

WindowState& state();

/** Queues an event for the next `glfwPollEvents`. Safe to call from any thread. */
void postEvent(const Event& event);

/**
 * Opens the desktop-GL translation layer at [path], or returns the handle already open.
 *
 * Must not be called from the render thread. gl4es probes the driver from an ELF constructor, and
 * that probe ends by calling `eglMakeCurrent(display, 0, 0, EGL_NO_CONTEXT)` on whichever thread
 * triggered the load. Opened lazily from the render thread — as `glfwGetProcAddress` used to — it
 * unbinds the context Minecraft has just made current, and the first framebuffer call then fails
 * with no status at all rather than an error.
 */
void* loadTranslationLayer(const char* path, const char* eglLibrary);

/** The translation layer opened by [loadTranslationLayer], or null when none was. */
void* translationLayer();

/**
 * The EGL implementation the shim drives, or `libEGL.so` when nothing else was selected.
 *
 * gl4es rewrites desktop GL onto the device's own GLES driver, so it wants Android's EGL and an ES
 * context. Zink is a Mesa driver: its GL entry points only work on a context that Mesa's own EGL
 * created, and that EGL is a different library. Neither can be chosen at link time.
 */
const char* eglLibrary();

/** Whether [eglLibrary] serves desktop OpenGL rather than OpenGL ES. */
bool eglServesDesktopGl();

} // namespace lodestone::glfw
