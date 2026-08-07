// The GLFW 3.4 entry points LWJGL binds to, implemented on an Android surface and EGL.
//
// LWJGL resolves these by name with `dlsym`, so the exported symbols and their signatures must
// match upstream GLFW exactly; the game is none the wiser. Only the calls Minecraft and LWJGL
// actually make are implemented — the rest return benign defaults rather than crashing, because a
// missing symbol would fail the whole `dlopen` and take the game down before it starts.

#include <EGL/egl.h>
#include <android/native_window.h>

#include <cstring>
#include <ctime>

#include "common/log.h"
#include "glfw/glfw_shim.h"

using lodestone::glfw::Event;
using lodestone::glfw::state;

// GLFW's opaque handles. Minecraft creates exactly one window and never inspects these, so their
// addresses are all that matter.
struct GLFWwindow;
struct GLFWmonitor;

namespace {

GLFWwindow* const kWindowHandle = reinterpret_cast<GLFWwindow*>(0x10DE01);
GLFWmonitor* const kMonitorHandle = reinterpret_cast<GLFWmonitor*>(0x10DE02);

using KeyCallback = void (*)(GLFWwindow*, int, int, int, int);
using CharCallback = void (*)(GLFWwindow*, unsigned int);
using MouseButtonCallback = void (*)(GLFWwindow*, int, int, int);
using CursorPosCallback = void (*)(GLFWwindow*, double, double);
using ScrollCallback = void (*)(GLFWwindow*, double, double);
using WindowSizeCallback = void (*)(GLFWwindow*, int, int);
using WindowFocusCallback = void (*)(GLFWwindow*, int);
using WindowCloseCallback = void (*)(GLFWwindow*);
using ErrorCallback = void (*)(int, const char*);

double monotonicSeconds() {
    timespec now{};
    clock_gettime(CLOCK_MONOTONIC, &now);
    return static_cast<double>(now.tv_sec) + static_cast<double>(now.tv_nsec) / 1e9;
}

const double kStartTime = monotonicSeconds();

/**
 * Brings up EGL against whatever surface the UI thread last published.
 *
 * Called only from the render thread. Returns false when there is no surface yet, which is normal
 * while the activity is starting or backgrounded.
 */
bool bindSurface(lodestone::glfw::WindowState& s) {
    ANativeWindow* window = nullptr;
    {
        std::lock_guard<std::mutex> lock(s.surfaceMutex);
        if (!s.surfaceDirty) {
            return s.surface != EGL_NO_SURFACE;
        }
        window = s.pendingWindow;
        s.pendingWindow = nullptr;
        s.surfaceDirty = false;
    }

    if (s.surface != EGL_NO_SURFACE) {
        eglMakeCurrent(s.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroySurface(s.display, s.surface);
        s.surface = EGL_NO_SURFACE;
    }
    if (s.nativeWindow != nullptr) {
        ANativeWindow_release(s.nativeWindow);
        s.nativeWindow = nullptr;
    }

    s.nativeWindow = window;
    if (window == nullptr) {
        return false;
    }

    s.surface = eglCreateWindowSurface(s.display, s.config, window, nullptr);
    if (s.surface == EGL_NO_SURFACE) {
        LOGE("eglCreateWindowSurface failed: 0x%04x", eglGetError());
        return false;
    }
    if (eglMakeCurrent(s.display, s.surface, s.surface, s.context) != EGL_TRUE) {
        LOGE("eglMakeCurrent failed: 0x%04x", eglGetError());
        return false;
    }
    eglSwapInterval(s.display, s.swapInterval.load());
    return true;
}

} // namespace

extern "C" {

// ---------------------------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------------------------

__attribute__((visibility("default"))) int glfwInit() {
    auto& s = state();
    if (s.initialised.load()) {
        return GLFW_TRUE;
    }

    s.display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (s.display == EGL_NO_DISPLAY || eglInitialize(s.display, nullptr, nullptr) != EGL_TRUE) {
        LOGE("eglInitialize failed: 0x%04x", eglGetError());
        return GLFW_FALSE;
    }

    // Minecraft needs a depth buffer and 8-bit colour; it manages its own stencil and multisample
    // state through framebuffer objects rather than the default framebuffer.
    const EGLint attributes[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 24,
        EGL_STENCIL_SIZE, 8,
        EGL_NONE,
    };
    EGLint configCount = 0;
    if (eglChooseConfig(s.display, attributes, &s.config, 1, &configCount) != EGL_TRUE ||
        configCount == 0) {
        LOGE("no suitable EGL config: 0x%04x", eglGetError());
        return GLFW_FALSE;
    }

    const EGLint contextAttributes[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    s.context = eglCreateContext(s.display, s.config, EGL_NO_CONTEXT, contextAttributes);
    if (s.context == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext failed: 0x%04x", eglGetError());
        return GLFW_FALSE;
    }

    s.initialised.store(true);
    LOGI("GLFW shim initialised");
    return GLFW_TRUE;
}

__attribute__((visibility("default"))) void glfwTerminate() {
    auto& s = state();
    if (!s.initialised.exchange(false)) {
        return;
    }
    eglMakeCurrent(s.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (s.surface != EGL_NO_SURFACE) {
        eglDestroySurface(s.display, s.surface);
        s.surface = EGL_NO_SURFACE;
    }
    if (s.context != EGL_NO_CONTEXT) {
        eglDestroyContext(s.display, s.context);
        s.context = EGL_NO_CONTEXT;
    }
    eglTerminate(s.display);
    s.display = EGL_NO_DISPLAY;
}

__attribute__((visibility("default"))) int glfwGetError(const char** description) {
    if (description != nullptr) {
        *description = nullptr;
    }
    return 0;
}

// ---------------------------------------------------------------------------------------------
// Window
// ---------------------------------------------------------------------------------------------

__attribute__((visibility("default"))) void glfwDefaultWindowHints() {}

// Hints describe a desktop window we do not have: the surface size is whatever Android gives us,
// and the context was already created with the only configuration the device supports.
__attribute__((visibility("default"))) void glfwWindowHint(int, int) {}
__attribute__((visibility("default"))) void glfwWindowHintString(int, const char*) {}

__attribute__((visibility("default"))) GLFWwindow* glfwCreateWindow(
        int width, int height, const char* title, GLFWmonitor*, GLFWwindow*) {
    auto& s = state();
    if (width > 0 && height > 0 && s.width.load() <= 0) {
        s.width.store(width);
        s.height.store(height);
    }
    LOGI("window requested: %dx%d (%s)", width, height, title != nullptr ? title : "");
    return kWindowHandle;
}

__attribute__((visibility("default"))) void glfwDestroyWindow(GLFWwindow*) {
    state().shouldClose.store(true);
}

__attribute__((visibility("default"))) int glfwWindowShouldClose(GLFWwindow*) {
    return state().shouldClose.load() ? GLFW_TRUE : GLFW_FALSE;
}

__attribute__((visibility("default"))) void glfwSetWindowShouldClose(GLFWwindow*, int value) {
    state().shouldClose.store(value != GLFW_FALSE);
}

__attribute__((visibility("default"))) void glfwGetWindowSize(
        GLFWwindow*, int* width, int* height) {
    if (width != nullptr) *width = state().width.load();
    if (height != nullptr) *height = state().height.load();
}

// The surface is always at native resolution, so the framebuffer and the window are the same size.
__attribute__((visibility("default"))) void glfwGetFramebufferSize(
        GLFWwindow* window, int* width, int* height) {
    glfwGetWindowSize(window, width, height);
}

__attribute__((visibility("default"))) void glfwGetWindowContentScale(
        GLFWwindow*, float* xscale, float* yscale) {
    // Minecraft applies its own GUI scale, and reporting anything but 1 would compound with it.
    if (xscale != nullptr) *xscale = 1.0f;
    if (yscale != nullptr) *yscale = 1.0f;
}

__attribute__((visibility("default"))) void glfwGetWindowPos(GLFWwindow*, int* x, int* y) {
    if (x != nullptr) *x = 0;
    if (y != nullptr) *y = 0;
}

// The window is the whole screen and cannot be moved, resized, iconified or decorated.
__attribute__((visibility("default"))) void glfwSetWindowPos(GLFWwindow*, int, int) {}
__attribute__((visibility("default"))) void glfwSetWindowSize(GLFWwindow*, int, int) {}
__attribute__((visibility("default"))) void glfwSetWindowTitle(GLFWwindow*, const char*) {}
__attribute__((visibility("default"))) void glfwSetWindowIcon(GLFWwindow*, int, const void*) {}
__attribute__((visibility("default"))) void glfwSetWindowSizeLimits(
        GLFWwindow*, int, int, int, int) {}
__attribute__((visibility("default"))) void glfwIconifyWindow(GLFWwindow*) {}
__attribute__((visibility("default"))) void glfwRestoreWindow(GLFWwindow*) {}
__attribute__((visibility("default"))) void glfwMaximizeWindow(GLFWwindow*) {}
__attribute__((visibility("default"))) void glfwShowWindow(GLFWwindow*) {}
__attribute__((visibility("default"))) void glfwHideWindow(GLFWwindow*) {}
__attribute__((visibility("default"))) void glfwFocusWindow(GLFWwindow*) {}
__attribute__((visibility("default"))) void glfwRequestWindowAttention(GLFWwindow*) {}
__attribute__((visibility("default"))) void glfwSetWindowMonitor(
        GLFWwindow*, GLFWmonitor*, int, int, int, int, int) {}

__attribute__((visibility("default"))) int glfwGetWindowAttrib(GLFWwindow*, int) {
    return GLFW_TRUE;
}

__attribute__((visibility("default"))) void glfwSetWindowAttrib(GLFWwindow*, int, int) {}

// ---------------------------------------------------------------------------------------------
// Context
// ---------------------------------------------------------------------------------------------

__attribute__((visibility("default"))) void glfwMakeContextCurrent(GLFWwindow* window) {
    auto& s = state();
    if (window == nullptr) {
        eglMakeCurrent(s.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        return;
    }
    // Binds lazily: the game makes its context current long before Android has a surface ready.
    bindSurface(s);
}

__attribute__((visibility("default"))) GLFWwindow* glfwGetCurrentContext() {
    return eglGetCurrentContext() != EGL_NO_CONTEXT ? kWindowHandle : nullptr;
}

__attribute__((visibility("default"))) void glfwSwapBuffers(GLFWwindow*) {
    auto& s = state();
    if (!bindSurface(s)) {
        // No surface: the activity is backgrounded. Returning immediately would spin the render
        // thread at full tilt, so wait for one to arrive instead.
        std::unique_lock<std::mutex> lock(s.surfaceMutex);
        s.surfaceChanged.wait_for(lock, std::chrono::milliseconds(100));
        return;
    }
    eglSwapBuffers(s.display, s.surface);
}

__attribute__((visibility("default"))) void glfwSwapInterval(int interval) {
    auto& s = state();
    s.swapInterval.store(interval);
    if (s.display != EGL_NO_DISPLAY) {
        eglSwapInterval(s.display, interval);
    }
}

__attribute__((visibility("default"))) void* glfwGetProcAddress(const char* name) {
    return reinterpret_cast<void*>(eglGetProcAddress(name));
}

__attribute__((visibility("default"))) int glfwExtensionSupported(const char*) {
    return GLFW_FALSE;
}

// ---------------------------------------------------------------------------------------------
// Events
// ---------------------------------------------------------------------------------------------

__attribute__((visibility("default"))) void glfwPollEvents() {
    auto& s = state();

    // The queue is drained into a local batch so callbacks — which run game logic and can be slow —
    // never hold the lock the UI thread needs to post the next event.
    std::deque<Event> batch;
    {
        std::lock_guard<std::mutex> lock(s.eventMutex);
        batch.swap(s.events);
    }

    for (const Event& event : batch) {
        switch (event.type) {
            case Event::Type::Key:
                if (s.keyCallback != nullptr) {
                    reinterpret_cast<KeyCallback>(s.keyCallback)(
                            kWindowHandle, event.a, event.b, event.c, event.d);
                }
                break;
            case Event::Type::Char:
                if (s.charCallback != nullptr) {
                    reinterpret_cast<CharCallback>(s.charCallback)(
                            kWindowHandle, static_cast<unsigned int>(event.a));
                }
                break;
            case Event::Type::MouseButton:
                if (s.mouseButtonCallback != nullptr) {
                    reinterpret_cast<MouseButtonCallback>(s.mouseButtonCallback)(
                            kWindowHandle, event.a, event.b, event.c);
                }
                break;
            case Event::Type::CursorPos:
                if (s.cursorPosCallback != nullptr) {
                    reinterpret_cast<CursorPosCallback>(s.cursorPosCallback)(
                            kWindowHandle, event.x, event.y);
                }
                break;
            case Event::Type::Scroll:
                if (s.scrollCallback != nullptr) {
                    reinterpret_cast<ScrollCallback>(s.scrollCallback)(
                            kWindowHandle, event.x, event.y);
                }
                break;
            case Event::Type::WindowSize:
                if (s.windowSizeCallback != nullptr) {
                    reinterpret_cast<WindowSizeCallback>(s.windowSizeCallback)(
                            kWindowHandle, event.a, event.b);
                }
                // The game rebuilds its render targets from the framebuffer callback, so both have
                // to fire for a resize to take effect.
                if (s.framebufferSizeCallback != nullptr) {
                    reinterpret_cast<WindowSizeCallback>(s.framebufferSizeCallback)(
                            kWindowHandle, event.a, event.b);
                }
                break;
            case Event::Type::WindowFocus:
                if (s.windowFocusCallback != nullptr) {
                    reinterpret_cast<WindowFocusCallback>(s.windowFocusCallback)(
                            kWindowHandle, event.a);
                }
                break;
            case Event::Type::WindowClose:
                if (s.windowCloseCallback != nullptr) {
                    reinterpret_cast<WindowCloseCallback>(s.windowCloseCallback)(kWindowHandle);
                }
                break;
        }
    }
}

__attribute__((visibility("default"))) void glfwWaitEvents() {
    glfwPollEvents();
}

__attribute__((visibility("default"))) void glfwWaitEventsTimeout(double) {
    glfwPollEvents();
}

__attribute__((visibility("default"))) void glfwPostEmptyEvent() {}

// ---------------------------------------------------------------------------------------------
// Input
// ---------------------------------------------------------------------------------------------

__attribute__((visibility("default"))) void glfwSetInputMode(GLFWwindow*, int mode, int value) {
    if (mode == GLFW_CURSOR) {
        // This is how the game tells us whether the player is looking around or in a menu, which
        // is what the touch overlay needs in order to interpret a drag.
        state().cursorMode.store(value);
    }
}

__attribute__((visibility("default"))) int glfwGetInputMode(GLFWwindow*, int mode) {
    return mode == GLFW_CURSOR ? state().cursorMode.load() : GLFW_FALSE;
}

__attribute__((visibility("default"))) int glfwRawMouseMotionSupported() {
    return GLFW_FALSE;
}

__attribute__((visibility("default"))) void glfwGetCursorPos(GLFWwindow*, double* x, double* y) {
    auto& s = state();
    std::lock_guard<std::mutex> lock(s.eventMutex);
    if (x != nullptr) *x = s.cursorX;
    if (y != nullptr) *y = s.cursorY;
}

__attribute__((visibility("default"))) void glfwSetCursorPos(GLFWwindow*, double x, double y) {
    auto& s = state();
    std::lock_guard<std::mutex> lock(s.eventMutex);
    s.cursorX = x;
    s.cursorY = y;
}

__attribute__((visibility("default"))) int glfwGetKey(GLFWwindow*, int key) {
    if (key < 0 || key >= 512) {
        return GLFW_RELEASE;
    }
    return state().keys[key].load();
}

__attribute__((visibility("default"))) int glfwGetMouseButton(GLFWwindow*, int button) {
    if (button < 0 || button >= 8) {
        return GLFW_RELEASE;
    }
    return state().mouseButtons[button].load();
}

__attribute__((visibility("default"))) const char* glfwGetKeyName(int, int) {
    return nullptr;
}

__attribute__((visibility("default"))) int glfwGetKeyScancode(int key) {
    return key;
}

// There is no system cursor to shape, and no desktop clipboard to share with.
__attribute__((visibility("default"))) void* glfwCreateStandardCursor(int) { return nullptr; }
__attribute__((visibility("default"))) void glfwDestroyCursor(void*) {}
__attribute__((visibility("default"))) void glfwSetCursor(GLFWwindow*, void*) {}

__attribute__((visibility("default"))) const char* glfwGetClipboardString(GLFWwindow*) {
    return "";
}

__attribute__((visibility("default"))) void glfwSetClipboardString(GLFWwindow*, const char*) {}

// ---------------------------------------------------------------------------------------------
// Callbacks
// ---------------------------------------------------------------------------------------------

#define DEFINE_CALLBACK_SETTER(name, field, type)                                   \
    __attribute__((visibility("default"))) void* name(GLFWwindow*, void* callback) { \
        void* previous = state().field;                                             \
        state().field = callback;                                                   \
        return previous;                                                            \
    }

DEFINE_CALLBACK_SETTER(glfwSetKeyCallback, keyCallback, KeyCallback)
DEFINE_CALLBACK_SETTER(glfwSetCharCallback, charCallback, CharCallback)
DEFINE_CALLBACK_SETTER(glfwSetMouseButtonCallback, mouseButtonCallback, MouseButtonCallback)
DEFINE_CALLBACK_SETTER(glfwSetCursorPosCallback, cursorPosCallback, CursorPosCallback)
DEFINE_CALLBACK_SETTER(glfwSetScrollCallback, scrollCallback, ScrollCallback)
DEFINE_CALLBACK_SETTER(glfwSetWindowSizeCallback, windowSizeCallback, WindowSizeCallback)
DEFINE_CALLBACK_SETTER(glfwSetFramebufferSizeCallback, framebufferSizeCallback, WindowSizeCallback)
DEFINE_CALLBACK_SETTER(glfwSetWindowFocusCallback, windowFocusCallback, WindowFocusCallback)
DEFINE_CALLBACK_SETTER(glfwSetWindowCloseCallback, windowCloseCallback, WindowCloseCallback)

#undef DEFINE_CALLBACK_SETTER

__attribute__((visibility("default"))) void* glfwSetErrorCallback(void* callback) {
    void* previous = state().errorCallback;
    state().errorCallback = callback;
    return previous;
}

// Events that cannot occur on a single full-screen surface with no desktop around it.
__attribute__((visibility("default"))) void* glfwSetCharModsCallback(GLFWwindow*, void*) { return nullptr; }
__attribute__((visibility("default"))) void* glfwSetCursorEnterCallback(GLFWwindow*, void*) { return nullptr; }
__attribute__((visibility("default"))) void* glfwSetDropCallback(GLFWwindow*, void*) { return nullptr; }
__attribute__((visibility("default"))) void* glfwSetWindowPosCallback(GLFWwindow*, void*) { return nullptr; }
__attribute__((visibility("default"))) void* glfwSetWindowIconifyCallback(GLFWwindow*, void*) { return nullptr; }
__attribute__((visibility("default"))) void* glfwSetWindowMaximizeCallback(GLFWwindow*, void*) { return nullptr; }
__attribute__((visibility("default"))) void* glfwSetWindowRefreshCallback(GLFWwindow*, void*) { return nullptr; }
__attribute__((visibility("default"))) void* glfwSetWindowContentScaleCallback(GLFWwindow*, void*) { return nullptr; }
__attribute__((visibility("default"))) void* glfwSetMonitorCallback(void*) { return nullptr; }
__attribute__((visibility("default"))) void* glfwSetJoystickCallback(void*) { return nullptr; }

// ---------------------------------------------------------------------------------------------
// Monitor
// ---------------------------------------------------------------------------------------------

namespace {
// GLFW hands out a pointer to a video mode it owns; the game reads it without copying, so this has
// to outlive the call.
struct VideoMode {
    int width;
    int height;
    int redBits;
    int greenBits;
    int blueBits;
    int refreshRate;
};
VideoMode g_videoMode{1280, 720, 8, 8, 8, 60};
} // namespace

__attribute__((visibility("default"))) GLFWmonitor* glfwGetPrimaryMonitor() {
    return kMonitorHandle;
}

__attribute__((visibility("default"))) GLFWmonitor** glfwGetMonitors(int* count) {
    static GLFWmonitor* monitors[] = {kMonitorHandle};
    if (count != nullptr) *count = 1;
    return monitors;
}

__attribute__((visibility("default"))) const void* glfwGetVideoMode(GLFWmonitor*) {
    g_videoMode.width = state().width.load();
    g_videoMode.height = state().height.load();
    return &g_videoMode;
}

__attribute__((visibility("default"))) const void* glfwGetVideoModes(GLFWmonitor*, int* count) {
    if (count != nullptr) *count = 1;
    return glfwGetVideoMode(nullptr);
}

__attribute__((visibility("default"))) void glfwGetMonitorPos(GLFWmonitor*, int* x, int* y) {
    if (x != nullptr) *x = 0;
    if (y != nullptr) *y = 0;
}

__attribute__((visibility("default"))) void glfwGetMonitorPhysicalSize(
        GLFWmonitor*, int* width, int* height) {
    // Roughly a 6-inch phone panel. Minecraft only uses this to derive a DPI it never acts on.
    if (width != nullptr) *width = 68;
    if (height != nullptr) *height = 122;
}

__attribute__((visibility("default"))) void glfwGetMonitorContentScale(
        GLFWmonitor*, float* xscale, float* yscale) {
    if (xscale != nullptr) *xscale = 1.0f;
    if (yscale != nullptr) *yscale = 1.0f;
}

__attribute__((visibility("default"))) void glfwGetMonitorWorkarea(
        GLFWmonitor*, int* x, int* y, int* width, int* height) {
    if (x != nullptr) *x = 0;
    if (y != nullptr) *y = 0;
    if (width != nullptr) *width = state().width.load();
    if (height != nullptr) *height = state().height.load();
}

__attribute__((visibility("default"))) const char* glfwGetMonitorName(GLFWmonitor*) {
    return "Android";
}

// ---------------------------------------------------------------------------------------------
// Time
// ---------------------------------------------------------------------------------------------

__attribute__((visibility("default"))) double glfwGetTime() {
    return monotonicSeconds() - kStartTime;
}

__attribute__((visibility("default"))) void glfwSetTime(double) {}

__attribute__((visibility("default"))) uint64_t glfwGetTimerValue() {
    timespec now{};
    clock_gettime(CLOCK_MONOTONIC, &now);
    return static_cast<uint64_t>(now.tv_sec) * 1000000000ull + static_cast<uint64_t>(now.tv_nsec);
}

__attribute__((visibility("default"))) uint64_t glfwGetTimerFrequency() {
    return 1000000000ull;
}

__attribute__((visibility("default"))) void glfwGetVersion(int* major, int* minor, int* revision) {
    // Reported as the GLFW release LWJGL 3.3+ expects, so its feature checks take the modern path.
    if (major != nullptr) *major = 3;
    if (minor != nullptr) *minor = 4;
    if (revision != nullptr) *revision = 0;
}

__attribute__((visibility("default"))) const char* glfwGetVersionString() {
    return "3.4.0 Lodestone Android shim";
}

// ---------------------------------------------------------------------------------------------
// Joystick — reported as absent; touch controls stand in for a gamepad.
// ---------------------------------------------------------------------------------------------

__attribute__((visibility("default"))) int glfwJoystickPresent(int) { return GLFW_FALSE; }
__attribute__((visibility("default"))) const float* glfwGetJoystickAxes(int, int* count) {
    if (count != nullptr) *count = 0;
    return nullptr;
}
__attribute__((visibility("default"))) const unsigned char* glfwGetJoystickButtons(int, int* count) {
    if (count != nullptr) *count = 0;
    return nullptr;
}
__attribute__((visibility("default"))) const char* glfwGetJoystickName(int) { return nullptr; }
__attribute__((visibility("default"))) const char* glfwGetJoystickGUID(int) { return nullptr; }
__attribute__((visibility("default"))) int glfwJoystickIsGamepad(int) { return GLFW_FALSE; }
__attribute__((visibility("default"))) int glfwGetGamepadState(int, void*) { return GLFW_FALSE; }
__attribute__((visibility("default"))) int glfwUpdateGamepadMappings(const char*) { return GLFW_FALSE; }

// ---------------------------------------------------------------------------------------------
// Vulkan
// ---------------------------------------------------------------------------------------------

__attribute__((visibility("default"))) int glfwVulkanSupported() {
    // Modern Minecraft ships a Vulkan renderer and Android drivers expose Vulkan natively, but the
    // surface plumbing is not wired up yet, so the game is steered to the GL path for now.
    return GLFW_FALSE;
}

__attribute__((visibility("default"))) const char** glfwGetRequiredInstanceExtensions(
        uint32_t* count) {
    if (count != nullptr) *count = 0;
    return nullptr;
}

} // extern "C"
