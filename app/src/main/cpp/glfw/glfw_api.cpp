// The GLFW 3.4 entry points LWJGL binds to, implemented on an Android surface and EGL.
//
// LWJGL resolves these by name with `dlsym`, so the exported symbols and their signatures must
// match upstream GLFW exactly; the game is none the wiser. Only the calls Minecraft and LWJGL
// actually make are implemented — the rest return benign defaults rather than crashing, because a
// missing symbol would fail the whole `dlopen` and take the game down before it starts.

#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/native_window.h>
#include <dlfcn.h>

#include <chrono>
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

// The EGL entry points the shim uses. Resolved by name rather than linked, because which library
// serves them depends on the translation layer: gl4es runs on Android's EGL, Zink only on Mesa's.
#define LODESTONE_EGL_FUNCTIONS(X)                                                                \
    X(eglGetDisplay) X(eglInitialize) X(eglTerminate) X(eglChooseConfig) X(eglGetConfigAttrib)    \
    X(eglCreateContext) X(eglDestroyContext) X(eglCreateWindowSurface)                            \
    X(eglCreatePbufferSurface) X(eglDestroySurface) X(eglMakeCurrent) X(eglSwapBuffers)           \
    X(eglSwapInterval) X(eglGetError) X(eglGetCurrentContext) X(eglGetProcAddress) X(eglBindAPI)

struct EglApi {
#define LODESTONE_DECLARE_EGL(name) decltype(&::name) name = nullptr;
    LODESTONE_EGL_FUNCTIONS(LODESTONE_DECLARE_EGL)
#undef LODESTONE_DECLARE_EGL
};

EglApi egl;

/**
 * Points [egl] at the EGL this shim is linked against.
 *
 * Android's is the only EGL in the process now. It used to be selectable, because Mesa's Zink
 * needed its own — but the game renders through Vulkan itself where it can, and gl4es forwards to
 * the device's own GL ES driver, which is what this EGL already drives.
 */
void loadEgl() {
#define LODESTONE_BIND_EGL(name) egl.name = &::name;
    LODESTONE_EGL_FUNCTIONS(LODESTONE_BIND_EGL)
#undef LODESTONE_BIND_EGL
}

/**
 * `glGetString` from the translation layer, falling back to the one linked in.
 *
 * Asking the layer rather than the system GLES matters: it is the layer's answer that Minecraft
 * will act on, and for Zink the linked GLES entry point belongs to a different driver entirely.
 */
const char* glString(GLenum name) {
    using GetString = const GLubyte* (*)(GLenum);
    static GetString getString = [] {
        if (void* library = lodestone::glfw::translationLayer()) {
            if (void* symbol = dlsym(library, "glGetString")) {
                return reinterpret_cast<GetString>(symbol);
            }
        }
        return static_cast<GetString>(&glGetString);
    }();
    const GLubyte* value = getString(name);
    return value != nullptr ? reinterpret_cast<const char*>(value) : "unavailable";
}

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
        if (s.boundRevision == s.windowRevision) {
            return s.surface != EGL_NO_SURFACE;
        }
        s.boundRevision = s.windowRevision;
        window = s.window;
        if (window != nullptr) {
            // EGL keeps the window across frames, and the UI thread is free to replace the
            // publication at any point, so the binding holds a reference of its own.
            ANativeWindow_acquire(window);
        }
    }

    if (s.surface != EGL_NO_SURFACE) {
        egl.eglMakeCurrent(s.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        egl.eglDestroySurface(s.display, s.surface);
        s.surface = EGL_NO_SURFACE;
    }
    if (s.nativeWindow != nullptr) {
        ANativeWindow_release(s.nativeWindow);
        s.nativeWindow = nullptr;
    }

    s.nativeWindow = window;
    if (window == nullptr) {
        // GLFW keeps a context current until the caller changes it, and Minecraft leans on that: it
        // makes GL calls between frames, including while the activity is backgrounded and Android
        // has reclaimed the surface. The placeholder keeps those calls landing in a live context.
        egl.eglMakeCurrent(s.display, s.placeholder, s.placeholder, s.context);
        return false;
    }

    // EGL requires the window's buffer format to match the config's native visual, and the
    // compositor will otherwise refuse the surface with EGL_BAD_MATCH.
    EGLint visualId = 0;
    egl.eglGetConfigAttrib(s.display, s.config, EGL_NATIVE_VISUAL_ID, &visualId);
    ANativeWindow_setBuffersGeometry(window, 0, 0, visualId);

    s.surface = egl.eglCreateWindowSurface(s.display, s.config, window, nullptr);
    if (s.surface == EGL_NO_SURFACE) {
        LOGE("eglCreateWindowSurface failed: 0x%04x", egl.eglGetError());
        return false;
    }
    if (egl.eglMakeCurrent(s.display, s.surface, s.surface, s.context) != EGL_TRUE) {
        LOGE("eglMakeCurrent failed: 0x%04x", egl.eglGetError());
        return false;
    }
    egl.eglSwapInterval(s.display, s.swapInterval.load());
    // Logged once per bind rather than at init: it is the only place that can report what the
    // driver actually gave us, and a context that goes missing later shows up as its absence.
    LOGI("EGL context current: %s / %s", glString(GL_VERSION), glString(GL_RENDERER));
    return true;
}

} // namespace

namespace lodestone::glfw {

bool initialiseEgl() {
    auto& s = state();
    if (s.initialised.load()) {
        return true;
    }

    loadEgl();

    s.display = egl.eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (s.display == EGL_NO_DISPLAY || egl.eglInitialize(s.display, nullptr, nullptr) != EGL_TRUE) {
        LOGE("eglInitialize failed: 0x%04x", egl.eglGetError());
        return false;
    }

    // Minecraft needs a depth buffer and 8-bit colour; it manages its own stencil and multisample
    // state through framebuffer objects rather than the default framebuffer.
    const EGLint attributes[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
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
    if (egl.eglChooseConfig(s.display, attributes, &s.config, 1, &configCount) != EGL_TRUE ||
        configCount == 0) {
        LOGE("no suitable EGL config: 0x%04x", egl.eglGetError());
        return false;
    }

    // A GL ES 3 context, which is what gl4es forwards desktop GL onto.
    const EGLint contextAttributes[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    s.context = egl.eglCreateContext(s.display, s.config, EGL_NO_CONTEXT, contextAttributes);
    if (s.context == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext failed: 0x%04x", egl.eglGetError());
        return false;
    }

    // One pixel, because nothing is ever meant to be read back from it: it exists so that a thread
    // between surfaces still has somewhere for its context to be current.
    const EGLint placeholderAttributes[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
    s.placeholder = egl.eglCreatePbufferSurface(s.display, s.config, placeholderAttributes);
    if (s.placeholder == EGL_NO_SURFACE) {
        LOGW("no placeholder surface: 0x%04x", egl.eglGetError());
    }

    s.initialised.store(true);
    LOGI("EGL up: GL ES 3 context");
    return true;
}

void shutdownEgl() {
    auto& s = state();
    s.initialised.store(false);

    if (s.display != EGL_NO_DISPLAY) {
        egl.eglMakeCurrent(s.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (s.surface != EGL_NO_SURFACE) {
            egl.eglDestroySurface(s.display, s.surface);
            s.surface = EGL_NO_SURFACE;
        }
        if (s.placeholder != EGL_NO_SURFACE) {
            egl.eglDestroySurface(s.display, s.placeholder);
            s.placeholder = EGL_NO_SURFACE;
        }
        if (s.context != EGL_NO_CONTEXT) {
            egl.eglDestroyContext(s.display, s.context);
            s.context = EGL_NO_CONTEXT;
        }
        egl.eglTerminate(s.display);
        s.display = EGL_NO_DISPLAY;
    }
    s.config = nullptr;
    egl = EglApi{};
}

} // namespace lodestone::glfw

extern "C" {

// ---------------------------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------------------------

/**
 * Always succeeds: the shim is up as soon as its library is loaded.
 *
 * Deliberately not a report on EGL. A GL renderer is settled in the Activity before the VM starts,
 * because it is only knowable by taking one as far as a live context — but the Vulkan backend
 * brings up no EGL at all, and either way `glfwInit` runs before `glfwWindowHint` has said which
 * of the two this launch is. Failing here would deny the game the backend it has not asked for yet.
 */
__attribute__((visibility("default"))) int glfwInit() {
    return GLFW_TRUE;
}

__attribute__((visibility("default"))) void glfwTerminate() {
    lodestone::glfw::shutdownEgl();
}

__attribute__((visibility("default"))) int glfwGetError(const char** description) {
    if (description != nullptr) {
        *description = nullptr;
    }
    return 0;
}

// The hints select a backend, an allocator and a joystick database, none of which this shim offers
// a choice about.
__attribute__((visibility("default"))) void glfwInitHint(int, int) {}
__attribute__((visibility("default"))) void glfwInitAllocator(const void*) {}

/**
 * X11, because the game only ever uses this to name the platform and to decide which desktop
 * quirks to work around.
 *
 * Reporting Wayland would make Minecraft skip the window icon and cursor calls this shim already
 * ignores, and reporting NULL would tell it there is no window system at all — which is the one
 * answer that changes behaviour, since the null backend has no context to render into.
 */
__attribute__((visibility("default"))) int glfwGetPlatform() {
    return GLFW_PLATFORM_X11;
}

__attribute__((visibility("default"))) int glfwPlatformSupported(int platform) {
    return platform == GLFW_PLATFORM_X11 ? GLFW_TRUE : GLFW_FALSE;
}

// ---------------------------------------------------------------------------------------------
// Window
// ---------------------------------------------------------------------------------------------

__attribute__((visibility("default"))) void glfwDefaultWindowHints() {}

// Hints describe a desktop window we do not have: the surface size is whatever Android gives us,
// and the context was already created with the only configuration the device supports.
__attribute__((visibility("default"))) void glfwWindowHint(int hint, int value) {
    // Every other hint describes a desktop window we do not have: the surface size is whatever
    // Android gives us, and a GL context was already created with the only configuration the
    // device supports. GLFW_CLIENT_API is the exception — GLFW_NO_API is how Minecraft's Vulkan
    // backend says it will create and present its own surface, and the shim must then keep out of
    // the way rather than binding EGL to the window underneath it.
    if (hint == GLFW_CLIENT_API) {
        state().clientApi.store(value);
    }
}
__attribute__((visibility("default"))) void glfwWindowHintString(int, const char*) {}

__attribute__((visibility("default"))) GLFWwindow* glfwCreateWindow(
        int width, int height, const char* title, GLFWmonitor*, GLFWwindow*) {
    auto& s = state();
    if (width > 0 && height > 0 && s.width.load() <= 0) {
        s.width.store(width);
        s.height.store(height);
    }
    s.title = title != nullptr ? title : "";
    LOGI("window requested: %dx%d (%s)", width, height, s.title.c_str());
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

// Android shows the activity's own label, so the title only has to survive a round trip. The
// returned pointer follows GLFW's contract: valid until the title is set again.
__attribute__((visibility("default"))) void glfwSetWindowTitle(GLFWwindow*, const char* title) {
    state().title = title != nullptr ? title : "";
}

__attribute__((visibility("default"))) const char* glfwGetWindowTitle(GLFWwindow*) {
    return state().title.c_str();
}

// The window is the whole screen and cannot be moved, resized, iconified or decorated.
__attribute__((visibility("default"))) void glfwSetWindowPos(GLFWwindow*, int, int) {}
__attribute__((visibility("default"))) void glfwSetWindowSize(GLFWwindow*, int, int) {}
__attribute__((visibility("default"))) void glfwSetWindowIcon(GLFWwindow*, int, const void*) {}
__attribute__((visibility("default"))) void glfwSetWindowSizeLimits(
        GLFWwindow*, int, int, int, int) {}
__attribute__((visibility("default"))) void glfwSetWindowAspectRatio(GLFWwindow*, int, int) {}
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

/**
 * Null, which is what GLFW reports for a window created without a monitor — as Minecraft's is.
 *
 * The surface does cover the screen, but claiming a monitor here would tell the game it is in a
 * fullscreen mode it did not ask for, and it would then try to leave it on the first frame.
 */
__attribute__((visibility("default"))) GLFWmonitor* glfwGetWindowMonitor(GLFWwindow*) {
    return nullptr;
}

// There is no decoration around the surface, so every edge is flush with the window.
__attribute__((visibility("default"))) void glfwGetWindowFrameSize(
        GLFWwindow*, int* left, int* top, int* right, int* bottom) {
    if (left != nullptr) *left = 0;
    if (top != nullptr) *top = 0;
    if (right != nullptr) *right = 0;
    if (bottom != nullptr) *bottom = 0;
}

__attribute__((visibility("default"))) float glfwGetWindowOpacity(GLFWwindow*) {
    return 1.0f;
}

__attribute__((visibility("default"))) void glfwSetWindowOpacity(GLFWwindow*, float) {}

// Stored rather than dropped: the pointer is the caller's, and handing back something it did not
// set would corrupt whatever it decides to cast the value to.
namespace {
void* g_windowUserPointer = nullptr;
void* g_monitorUserPointer = nullptr;
} // namespace

__attribute__((visibility("default"))) void glfwSetWindowUserPointer(GLFWwindow*, void* pointer) {
    g_windowUserPointer = pointer;
}

__attribute__((visibility("default"))) void* glfwGetWindowUserPointer(GLFWwindow*) {
    return g_windowUserPointer;
}

// ---------------------------------------------------------------------------------------------
// Context
// ---------------------------------------------------------------------------------------------

// The context calls all begin by checking that there is an EGL to talk to. Under the Vulkan
// backend there is deliberately none — `EglApi`'s members are still null pointers — and while
// Minecraft does not call these on that path, a missing guard would turn any stray call into a
// jump through address zero rather than the no-op GLFW promises for a window with no context.

__attribute__((visibility("default"))) void glfwMakeContextCurrent(GLFWwindow* window) {
    auto& s = state();
    if (!s.initialised.load()) {
        return;
    }
    if (window == nullptr) {
        egl.eglMakeCurrent(s.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        return;
    }
    // Binds lazily: the game makes its context current long before Android has a surface ready.
    bindSurface(s);
}

__attribute__((visibility("default"))) GLFWwindow* glfwGetCurrentContext() {
    if (!state().initialised.load()) {
        return nullptr;
    }
    return egl.eglGetCurrentContext() != EGL_NO_CONTEXT ? kWindowHandle : nullptr;
}

__attribute__((visibility("default"))) void glfwSwapBuffers(GLFWwindow*) {
    auto& s = state();
    if (!s.initialised.load()) {
        return;
    }
    if (!bindSurface(s)) {
        // No surface: the activity is backgrounded. Returning immediately would spin the render
        // thread at full tilt, so wait for one to arrive instead.
        std::unique_lock<std::mutex> lock(s.surfaceMutex);
        s.surfaceChanged.wait_for(lock, std::chrono::milliseconds(100));
        return;
    }
    egl.eglSwapBuffers(s.display, s.surface);
}

__attribute__((visibility("default"))) void glfwSwapInterval(int interval) {
    auto& s = state();
    s.swapInterval.store(interval);
    if (s.initialised.load() && s.display != EGL_NO_DISPLAY) {
        egl.eglSwapInterval(s.display, interval);
    }
}

__attribute__((visibility("default"))) void* glfwGetProcAddress(const char* name) {
    // The translation layer first: it owns the desktop entry points the game is asking for. EGL is
    // only a fallback for the handful of extension functions the layer passes straight through.
    if (void* library = lodestone::glfw::translationLayer()) {
        if (void* symbol = dlsym(library, name)) {
            return symbol;
        }
    }
    return reinterpret_cast<void*>(egl.eglGetProcAddress(name));
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

/**
 * The name of a printable key, or null for one that has no printed character.
 *
 * Minecraft leans on this harder than it looks. Its language files carry a `key.keyboard.*` entry
 * for the named keys — space, escape, the function keys — but none for letters, digits or
 * punctuation, because on a desktop GLFW reports those from the active keyboard layout. A shim that
 * returned null for everything left the game with nothing to show but the translation key itself,
 * which is why the tutorial hint read "Move with key.keyboard.w".
 *
 * The names are the ones GLFW produces for a US layout, which is the layout an on-screen control
 * overlay is: the buttons send `GLFW_KEY_W` because they are labelled W. A physical keyboard with
 * another layout would want its own names, and that has to come from Android's key character map
 * rather than from a table here — but reporting nothing at all is not the better answer for it.
 *
 * The returned pointer outlives the call, as GLFW's contract requires.
 */
__attribute__((visibility("default"))) const char* glfwGetKeyName(int key, int) {
    // Letters are GLFW_KEY_A..Z, which are the ASCII uppercase values, and GLFW names them in
    // lowercase — the character the key produces unshifted.
    if (key >= 'A' && key <= 'Z') {
        static char names[26][2];
        char* name = names[key - 'A'];
        name[0] = static_cast<char>(key - 'A' + 'a');
        name[1] = '\0';
        return name;
    }
    if (key >= '0' && key <= '9') {
        static char names[10][2];
        char* name = names[key - '0'];
        name[0] = static_cast<char>(key);
        name[1] = '\0';
        return name;
    }

    switch (key) {
        case 39: return "'";
        case 44: return ",";
        case 45: return "-";
        case 46: return ".";
        case 47: return "/";
        case 59: return ";";
        case 61: return "=";
        case 91: return "[";
        case 92: return "\\";
        case 93: return "]";
        case 96: return "`";
        // The keypad, which GLFW names by the character it produces rather than by its position.
        case 320: return "0";
        case 321: return "1";
        case 322: return "2";
        case 323: return "3";
        case 324: return "4";
        case 325: return "5";
        case 326: return "6";
        case 327: return "7";
        case 328: return "8";
        case 329: return "9";
        case 330: return ".";
        case 331: return "/";
        case 332: return "*";
        case 333: return "-";
        case 334: return "+";
        case 336: return "=";
        // Everything else — the modifiers, the function keys, the arrows — has no printed
        // character, and GLFW answers null so the caller falls back to a name of its own.
        default: return nullptr;
    }
}

__attribute__((visibility("default"))) int glfwGetKeyScancode(int key) {
    return key;
}

// There is no system cursor to shape, and no desktop clipboard to share with.
__attribute__((visibility("default"))) void* glfwCreateCursor(const void*, int, int) {
    return nullptr;
}
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
// GLFW 3.4's IME extension. LWJGL resolves these seven with the *optional* variant of its symbol
// lookup, so a shim without them leaves the addresses at zero rather than failing to load — and
// Minecraft calls three of them while building its window. With LWJGL's checks off that is a jump
// to address zero on the render thread, which is what it did here: a two-frame tombstone with our
// window handle in x0 and nothing to say which function was meant.
//
// Composition is not wired to Android's input method yet; these keep the registration contract
// (store the callback, hand back the previous one) so the game gets an answer and the IME simply
// reports nothing happening.
DEFINE_CALLBACK_SETTER(glfwSetPreeditCallback, preeditCallback, void)
DEFINE_CALLBACK_SETTER(glfwSetIMEStatusCallback, imeStatusCallback, void)
DEFINE_CALLBACK_SETTER(glfwSetPreeditCandidateCallback, preeditCandidateCallback, void)
DEFINE_CALLBACK_SETTER(glfwSetMouseButtonCallback, mouseButtonCallback, MouseButtonCallback)
DEFINE_CALLBACK_SETTER(glfwSetCursorPosCallback, cursorPosCallback, CursorPosCallback)
DEFINE_CALLBACK_SETTER(glfwSetScrollCallback, scrollCallback, ScrollCallback)
DEFINE_CALLBACK_SETTER(glfwSetWindowSizeCallback, windowSizeCallback, WindowSizeCallback)
DEFINE_CALLBACK_SETTER(glfwSetFramebufferSizeCallback, framebufferSizeCallback, WindowSizeCallback)
DEFINE_CALLBACK_SETTER(glfwSetWindowFocusCallback, windowFocusCallback, WindowFocusCallback)
DEFINE_CALLBACK_SETTER(glfwSetWindowCloseCallback, windowCloseCallback, WindowCloseCallback)

#undef DEFINE_CALLBACK_SETTER

// Where the candidate window would go, in window coordinates. Recorded so that the getter answers
// with what the game set, which is all GLFW promises; Android places its own candidate window.
namespace {
int g_preeditX = 0;
int g_preeditY = 0;
int g_preeditWidth = 0;
int g_preeditHeight = 0;
} // namespace

__attribute__((visibility("default"))) void glfwSetPreeditCursorRectangle(
        GLFWwindow*, int x, int y, int width, int height) {
    g_preeditX = x;
    g_preeditY = y;
    g_preeditWidth = width;
    g_preeditHeight = height;
}

__attribute__((visibility("default"))) void glfwGetPreeditCursorRectangle(
        GLFWwindow*, int* x, int* y, int* width, int* height) {
    if (x != nullptr) *x = g_preeditX;
    if (y != nullptr) *y = g_preeditY;
    if (width != nullptr) *width = g_preeditWidth;
    if (height != nullptr) *height = g_preeditHeight;
}

/** Nothing is being composed, so there is nothing to discard. */
__attribute__((visibility("default"))) void glfwResetPreeditText(GLFWwindow*) {}

/**
 * Null, which GLFW returns for an index with no candidate behind it.
 *
 * The count is written first because a caller reads it whether or not it gets a pointer back.
 */
__attribute__((visibility("default"))) const unsigned int* glfwGetPreeditCandidate(
        GLFWwindow*, int, int* textCount) {
    if (textCount != nullptr) {
        *textCount = 0;
    }
    return nullptr;
}

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

__attribute__((visibility("default"))) void glfwSetMonitorUserPointer(
        GLFWmonitor*, void* pointer) {
    g_monitorUserPointer = pointer;
}

__attribute__((visibility("default"))) void* glfwGetMonitorUserPointer(GLFWmonitor*) {
    return g_monitorUserPointer;
}

// Android composites through SurfaceFlinger, which owns the panel's transfer function; there is no
// per-application ramp to read or write. GLFW returns null on failure, which callers already handle.
__attribute__((visibility("default"))) const void* glfwGetGammaRamp(GLFWmonitor*) {
    return nullptr;
}
__attribute__((visibility("default"))) void glfwSetGammaRamp(GLFWmonitor*, const void*) {}
__attribute__((visibility("default"))) void glfwSetGamma(GLFWmonitor*, float) {}

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
__attribute__((visibility("default"))) const unsigned char* glfwGetJoystickHats(int, int* count) {
    if (count != nullptr) *count = 0;
    return nullptr;
}
__attribute__((visibility("default"))) const char* glfwGetJoystickName(int) { return nullptr; }
__attribute__((visibility("default"))) const char* glfwGetGamepadName(int) { return nullptr; }
__attribute__((visibility("default"))) void glfwSetJoystickUserPointer(int, void*) {}
__attribute__((visibility("default"))) void* glfwGetJoystickUserPointer(int) { return nullptr; }
__attribute__((visibility("default"))) const char* glfwGetJoystickGUID(int) { return nullptr; }
__attribute__((visibility("default"))) int glfwJoystickIsGamepad(int) { return GLFW_FALSE; }
__attribute__((visibility("default"))) int glfwGetGamepadState(int, void*) { return GLFW_FALSE; }
__attribute__((visibility("default"))) int glfwUpdateGamepadMappings(const char*) { return GLFW_FALSE; }

// The Vulkan entry points live in glfw_vulkan.cpp, which owns the Android surface extension and
// the loader they all go through.

} // extern "C"
