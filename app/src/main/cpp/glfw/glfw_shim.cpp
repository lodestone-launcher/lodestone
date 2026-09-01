#include "glfw/glfw_shim.h"

#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <jni.h>

#include <chrono>
#include <cstdlib>
#include <string>
#include <vector>

#include "common/log.h"

namespace lodestone::glfw {
namespace {

std::mutex g_layerMutex;
void* g_layer = nullptr;
bool g_selected = false;

/**
 * Puts the environment a candidate reads into place before anything opens it.
 *
 * Both layers work this out once, as they come up, so nothing here can be deferred. Setting them
 * per candidate rather than baking them in at build time keeps one binary working across vendors.
 */
void applyEnvironment(bool desktopGl) {
    if (desktopGl) {
        // Zink is the only Gallium driver built, but the loader still probes for a hardware driver
        // by name first and would find none.
        setenv("GALLIUM_DRIVER", "zink", 1);
        setenv("MESA_LOADER_DRIVER_OVERRIDE", "zink", 1);
        // Zink advertises whatever the Vulkan driver underneath can support, which it works out
        // lazily; the overrides stop Mesa capping the profile below what Minecraft asks for.
        setenv("MESA_GL_VERSION_OVERRIDE", "4.6", 1);
        setenv("MESA_GLSL_VERSION_OVERRIDE", "460", 1);
        setenv("GALLIUM_THREAD", "0", 1);
        // Deliberately not pointed at a bundled driver. Zink runs on whatever libvulkan.so the
        // device has, which is the same loader the Vulkan backend already renders 26.2 through —
        // and unlike a driver of our own, every device has one.
        return;
    }

    // gl4es reads these to find the drivers it forwards to.
    setenv("LIBGL_GLES", "libGLESv2.so", 1);
    setenv("LIBGL_EGL", "libEGL.so", 1);
    // Minecraft compiles its own shaders, so gl4es's fixed-pipeline emulation is not needed and
    // its shader conversion path is what matters.
    setenv("LIBGL_NOBANNER", "1", 1);
}

} // namespace

WindowState& state() {
    static WindowState instance;
    return instance;
}

int selectRenderer(const std::vector<RendererCandidate>& candidates) {
    std::lock_guard<std::mutex> lock(g_layerMutex);
    if (g_selected) {
        return -1;
    }
    g_selected = true;

    if (candidates.empty()) {
        // A Vulkan launch. The game brings up its own device and presents to the window itself, so
        // there is deliberately no EGL here for it to trip over.
        return -1;
    }

    for (size_t index = 0; index < candidates.size(); ++index) {
        const RendererCandidate& candidate = candidates[index];
        const bool desktopGl = !candidate.eglLibrary.empty();
        applyEnvironment(desktopGl);

        if (!initialiseEgl(candidate.eglLibrary.c_str(), desktopGl)) {
            LOGE("renderer %s did not come up; trying the next", candidate.id.c_str());
            shutdownEgl();
            continue;
        }

        // Only now, because a layer that has been dlopened cannot be taken back out of the process:
        // its constructors have run, and with RTLD_GLOBAL its `glFoo` symbols would answer lookups
        // meant for whichever renderer is settled on instead.
        g_layer = dlopen(candidate.layerPath.c_str(), RTLD_NOW | RTLD_GLOBAL);
        if (g_layer == nullptr) {
            LOGE("renderer %s has no layer at %s: %s", candidate.id.c_str(),
                 candidate.layerPath.c_str(), dlerror());
            shutdownEgl();
            continue;
        }

        LOGI("renderer %s selected (%s)", candidate.id.c_str(), candidate.layerPath.c_str());
        return static_cast<int>(index);
    }

    LOGE("no renderer came up out of %zu tried", candidates.size());
    return -1;
}

void* translationLayer() {
    std::lock_guard<std::mutex> lock(g_layerMutex);
    return g_layer;
}

ANativeWindow* acquireWindow(int timeoutMillis) {
    WindowState& s = state();
    std::unique_lock<std::mutex> lock(s.surfaceMutex);
    if (s.window == nullptr) {
        s.surfaceChanged.wait_for(lock, std::chrono::milliseconds(timeoutMillis),
                                  [&s] { return s.window != nullptr; });
    }
    if (s.window == nullptr) {
        return nullptr;
    }
    // The caller keeps this past the lock, and the UI thread is free to replace the publication in
    // the meantime, so it leaves with a reference of its own rather than a borrowed pointer.
    ANativeWindow_acquire(s.window);
    return s.window;
}

void postEvent(const Event& event) {
    WindowState& s = state();
    std::lock_guard<std::mutex> lock(s.eventMutex);
    // A game that stops polling — because it is loading a world, or has hung — must not let the UI
    // thread grow this queue without bound.
    if (s.events.size() > 4096) {
        s.events.pop_front();
    }
    s.events.push_back(event);
}

} // namespace lodestone::glfw

using lodestone::glfw::Event;
using lodestone::glfw::state;

namespace {

/** Records key and button state so `glfwGetKey` can answer between callbacks. */
void recordKey(int key, int action) {
    if (key >= 0 && key < 512) {
        state().keys[key].store(action == GLFW_RELEASE ? GLFW_RELEASE : GLFW_PRESS);
    }
}

void recordMouseButton(int button, int action) {
    if (button >= 0 && button < 8) {
        state().mouseButtons[button].store(action == GLFW_RELEASE ? GLFW_RELEASE : GLFW_PRESS);
    }
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL Java_com_github_lodestone_runtime_GlfwBridge_nativeSelectRenderer(
        JNIEnv* env, jclass, jobjectArray ids, jobjectArray layerPaths,
        jobjectArray eglLibraries) {
    // Copied out of the JVM up front, because bringing a renderer up runs driver code that can take
    // its time, and holding pinned string bodies across that is what `GetStringUTFChars` asks not
    // to be done.
    const auto read = [env](jobjectArray array, jsize index) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(array, index));
        if (value == nullptr) {
            return std::string();
        }
        const char* chars = env->GetStringUTFChars(value, nullptr);
        std::string copy = chars != nullptr ? chars : "";
        env->ReleaseStringUTFChars(value, chars);
        env->DeleteLocalRef(value);
        return copy;
    };

    std::vector<lodestone::glfw::RendererCandidate> candidates;
    const jsize count = ids != nullptr ? env->GetArrayLength(ids) : 0;
    candidates.reserve(static_cast<size_t>(count));
    for (jsize index = 0; index < count; ++index) {
        candidates.push_back({read(ids, index), read(layerPaths, index), read(eglLibraries, index)});
    }

    return lodestone::glfw::selectRenderer(candidates);
}

JNIEXPORT void JNICALL Java_com_github_lodestone_runtime_GlfwBridge_nativeSetSurface(
        JNIEnv* env, jclass, jobject surface, jint width, jint height) {
    auto& s = state();

    ANativeWindow* window = nullptr;
    if (surface != nullptr) {
        // Takes a reference the render thread releases once it has finished with the surface, so
        // the compositor cannot reclaim it while EGL still holds a window surface on it.
        window = ANativeWindow_fromSurface(env, surface);
    }

    {
        std::lock_guard<std::mutex> lock(s.surfaceMutex);
        if (s.window != nullptr) {
            ANativeWindow_release(s.window);
        }
        s.window = window;
        ++s.windowRevision;
    }
    s.surfaceChanged.notify_all();

    if (width > 0 && height > 0) {
        const int previousWidth = s.width.exchange(width);
        const int previousHeight = s.height.exchange(height);
        if (previousWidth != width || previousHeight != height) {
            Event resize{Event::Type::WindowSize};
            resize.a = width;
            resize.b = height;
            lodestone::glfw::postEvent(resize);
        }
    }
    LOGI("surface %s (%dx%d)", surface != nullptr ? "attached" : "detached", width, height);
}

JNIEXPORT void JNICALL Java_com_github_lodestone_runtime_GlfwBridge_nativeSetSize(
        JNIEnv*, jclass, jint width, jint height) {
    if (width <= 0 || height <= 0) {
        return;
    }
    auto& s = state();
    s.width.store(width);
    s.height.store(height);

    Event resize{Event::Type::WindowSize};
    resize.a = width;
    resize.b = height;
    lodestone::glfw::postEvent(resize);
}

JNIEXPORT void JNICALL Java_com_github_lodestone_runtime_GlfwBridge_nativeSendKey(
        JNIEnv*, jclass, jint key, jint scancode, jint action, jint mods) {
    recordKey(key, action);

    Event event{Event::Type::Key};
    event.a = key;
    event.b = scancode;
    event.c = action;
    event.d = mods;
    lodestone::glfw::postEvent(event);
}

JNIEXPORT void JNICALL Java_com_github_lodestone_runtime_GlfwBridge_nativeSendChar(
        JNIEnv*, jclass, jint codepoint) {
    Event event{Event::Type::Char};
    event.a = codepoint;
    lodestone::glfw::postEvent(event);
}

JNIEXPORT void JNICALL Java_com_github_lodestone_runtime_GlfwBridge_nativeSendCursorPos(
        JNIEnv*, jclass, jfloat x, jfloat y) {
    auto& s = state();
    Event event{Event::Type::CursorPos};
    {
        std::lock_guard<std::mutex> lock(s.eventMutex);
        s.cursorX = x;
        s.cursorY = y;
        event.x = x;
        event.y = y;
    }
    lodestone::glfw::postEvent(event);
}

JNIEXPORT void JNICALL Java_com_github_lodestone_runtime_GlfwBridge_nativeSendCursorDelta(
        JNIEnv*, jclass, jfloat dx, jfloat dy) {
    auto& s = state();
    Event event{Event::Type::CursorPos};
    {
        std::lock_guard<std::mutex> lock(s.eventMutex);
        // While the pointer is grabbed the game reads deltas from an ever-growing virtual position,
        // so this must accumulate rather than clamp to the screen.
        s.cursorX += dx;
        s.cursorY += dy;
        event.x = s.cursorX;
        event.y = s.cursorY;
    }
    lodestone::glfw::postEvent(event);
}

JNIEXPORT void JNICALL Java_com_github_lodestone_runtime_GlfwBridge_nativeSendMouseButton(
        JNIEnv*, jclass, jint button, jint action, jint mods) {
    recordMouseButton(button, action);

    Event event{Event::Type::MouseButton};
    event.a = button;
    event.b = action;
    event.c = mods;
    lodestone::glfw::postEvent(event);
}

JNIEXPORT void JNICALL Java_com_github_lodestone_runtime_GlfwBridge_nativeSendScroll(
        JNIEnv*, jclass, jfloat dx, jfloat dy) {
    Event event{Event::Type::Scroll};
    event.x = dx;
    event.y = dy;
    lodestone::glfw::postEvent(event);
}

JNIEXPORT jboolean JNICALL Java_com_github_lodestone_runtime_GlfwBridge_nativeIsCursorGrabbed(
        JNIEnv*, jclass) {
    return state().cursorMode.load() == GLFW_CURSOR_DISABLED ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_github_lodestone_runtime_GlfwBridge_nativeRequestClose(
        JNIEnv*, jclass) {
    state().shouldClose.store(true);
    lodestone::glfw::postEvent(Event{Event::Type::WindowClose});
}

} // extern "C"
