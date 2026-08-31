#pragma once

#include <EGL/egl.h>
#include <android/native_window.h>

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <mutex>
#include <string>
#include <vector>

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
    // The Android UI thread publishes the window here and the render thread reads it. Which
    // graphics API consumes it is not this layer's business: EGL binds a window surface to it,
    // and Vulkan hands it to `vkCreateAndroidSurfaceKHR`. Both take a reference of their own,
    // because either may outlive the publication the UI thread later replaces.
    //
    // `windowRevision` counts publications rather than flagging one, so a consumer can tell "the
    // window I am holding is still the current one" from "there is a new one to pick up" without
    // the UI thread having to know who is listening.
    std::mutex surfaceMutex;
    std::condition_variable surfaceChanged;
    /** The current window, holding one reference owned by this field. Null while detached. */
    ANativeWindow* window = nullptr;
    std::uint64_t windowRevision = 0;

    /**
     * Which graphics API the game asked `glfwCreateWindow` for.
     *
     * Minecraft's Vulkan backend sets `GLFW_NO_API`, which on a real GLFW means "create no context
     * and leave presentation to me". Here it means the same thing, and it is the switch that keeps
     * the shim from bringing EGL up underneath a game that is about to drive Vulkan itself.
     */
    std::atomic_int clientApi{GLFW_OPENGL_API};

    // --- EGL, owned exclusively by the render thread -------------------------------------------
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLSurface surface = EGL_NO_SURFACE;
    /** Stands in for the window surface whenever Android has taken the real one away. */
    EGLSurface placeholder = EGL_NO_SURFACE;
    EGLContext context = EGL_NO_CONTEXT;
    EGLConfig config = nullptr;
    /** EGL's own reference to [window], acquired when a surface is bound to it. */
    ANativeWindow* nativeWindow = nullptr;
    /** The publication [nativeWindow] came from, so a repeat bind can be skipped. */
    std::uint64_t boundRevision = 0;

    std::atomic_int width{1280};
    std::atomic_int height{720};
    std::atomic_int swapInterval{1};

    // Nothing displays this, but GLFW 3.4 lets the title be read back and LWJGL resolves
    // `glfwGetWindowTitle` eagerly, so it has to exist and answer with what was set.
    std::string title;

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
    // GLFW 3.4's IME extension. Held rather than used: registration has to give the game back
    // whichever callback it replaced, and Android composition is not routed here yet.
    void* preeditCallback = nullptr;
    void* imeStatusCallback = nullptr;
    void* preeditCandidateCallback = nullptr;
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
 * One desktop-GL translation layer the shim can bring up over Android's EGL.
 *
 * Only reached on the OpenGL path. A Vulkan launch passes no candidates at all: the game creates
 * its own device and surface, and the shim never brings EGL up.
 */
struct RendererCandidate {
    std::string id;
    std::string layerPath;
};

/**
 * Brings up the first of [candidates] that works, and returns its index — or -1 if none did.
 *
 * Whether a renderer works is not a question of whether its libraries are on disk. Zink is packaged
 * and loads perfectly well on a device whose Vulkan driver it cannot drive, and only says so when
 * `eglInitialize` fails; that used to reach the game as "Failed to initialize GLFW" with no way
 * back. So each candidate is taken as far as a live EGL context before it is believed, and one that
 * does not get there is torn down completely and the next is tried.
 *
 * An empty list brings up Android's EGL with no layer at all, for a game that talks to the driver
 * itself.
 *
 * Must not be called from the render thread. gl4es probes the driver from an ELF constructor, and
 * that probe ends by calling `eglMakeCurrent(display, 0, 0, EGL_NO_CONTEXT)` on whichever thread
 * triggered the load. Opened lazily from the render thread — as `glfwGetProcAddress` used to — it
 * unbinds the context Minecraft has just made current, and the first framebuffer call then fails
 * with no status at all rather than an error.
 */
int selectRenderer(const std::vector<RendererCandidate>& candidates);

/** The translation layer [selectRenderer] settled on, or null when none was. */
void* translationLayer();

/**
 * Brings up Android's EGL with a GL ES 3 context.
 *
 * Implemented beside the rest of the EGL plumbing in glfw_api.cpp, and only meant for
 * [selectRenderer] to call.
 */
bool initialiseEgl();

/** Undoes [initialiseEgl] completely, so that another candidate can be tried on a clean slate. */
void shutdownEgl();

/**
 * Waits up to [timeoutMillis] for a window to be published and returns a reference to it.
 *
 * The caller owns the returned reference and must release it. Null means the wait ran out with the
 * activity still holding no surface.
 *
 * This exists for the Vulkan path, which — unlike EGL — has nowhere to park a context while the
 * window is away: `vkCreateAndroidSurfaceKHR` needs a real window at the moment the game asks for
 * its surface, and the game asks once, early, on its own schedule.
 */
ANativeWindow* acquireWindow(int timeoutMillis);

} // namespace lodestone::glfw
