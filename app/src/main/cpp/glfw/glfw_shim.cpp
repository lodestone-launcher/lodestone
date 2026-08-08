#include "glfw/glfw_shim.h"

#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <jni.h>

#include <cstdlib>
#include <string>

#include "common/log.h"

namespace lodestone::glfw {
namespace {

std::mutex g_layerMutex;
void* g_layer = nullptr;
bool g_layerOpened = false;
std::string g_eglLibrary = "libEGL.so";
bool g_desktopGl = false;

} // namespace

WindowState& state() {
    static WindowState instance;
    return instance;
}

void* loadTranslationLayer(const char* path, const char* eglLibrary) {
    std::lock_guard<std::mutex> lock(g_layerMutex);
    if (g_layerOpened) {
        return g_layer;
    }
    g_layerOpened = true;

    if (eglLibrary != nullptr && *eglLibrary != '\0') {
        g_eglLibrary = eglLibrary;
        g_desktopGl = true;
        // Mesa reads these while the driver comes up, so they have to be set before anything opens
        // it. Zink is the only Gallium driver built, but the loader still probes for a hardware
        // driver by name first and would find none.
        setenv("GALLIUM_DRIVER", "zink", 0);
        setenv("MESA_LOADER_DRIVER_OVERRIDE", "zink", 0);
        // Zink advertises whatever the Vulkan driver underneath can support, which it works out
        // lazily; the overrides stop Mesa capping the profile below what Minecraft asks for.
        setenv("MESA_GL_VERSION_OVERRIDE", "4.6COMPAT", 0);
        setenv("MESA_GLSL_VERSION_OVERRIDE", "460", 0);
        setenv("GALLIUM_THREAD", "0", 0);
        // Android's Vulkan loader only offers the vendor driver, and Qualcomm's exposes no
        // VK_EXT_external_memory_dma_buf. Zink needs it to render into the window's gralloc buffer:
        // without it every import fails, the game draws into an off-screen buffer of Mesa's own and
        // the surface is presented untouched — a black screen. Turnip supports it, so ship it and
        // point Zink at it, leaving the loader's default for devices we have no driver for.
        setenv("ZINK_VULKAN_LIBRARY", "libvulkan_freedreno.so", 0);
    } else {
        // gl4es reads these to find the drivers it forwards to. Setting them here rather than
        // baking them in at build time keeps one binary working across vendors, and they have to be
        // in the environment before the constructor runs.
        setenv("LIBGL_GLES", "libGLESv2.so", 0);
        setenv("LIBGL_EGL", "libEGL.so", 0);
        // Minecraft compiles its own shaders, so gl4es's fixed-pipeline emulation is not needed and
        // its shader conversion path is what matters.
        setenv("LIBGL_NOBANNER", "1", 0);
    }

    // RTLD_GLOBAL so that LWJGL's own `dlsym(RTLD_DEFAULT, "glFoo")` lookups also land here.
    g_layer = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    if (g_layer == nullptr) {
        LOGE("translation layer %s not available: %s", path, dlerror());
    } else {
        LOGI("translation layer %s loaded", path);
    }
    return g_layer;
}

void* translationLayer() {
    std::lock_guard<std::mutex> lock(g_layerMutex);
    return g_layer;
}

const char* eglLibrary() {
    std::lock_guard<std::mutex> lock(g_layerMutex);
    return g_eglLibrary.c_str();
}

bool eglServesDesktopGl() {
    std::lock_guard<std::mutex> lock(g_layerMutex);
    return g_desktopGl;
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

JNIEXPORT jboolean JNICALL
Java_com_github_lodestone_runtime_GlfwBridge_nativeLoadTranslationLayer(
        JNIEnv* env, jclass, jstring path, jstring eglLibrary) {
    const char* pathChars = env->GetStringUTFChars(path, nullptr);
    const char* eglChars =
            eglLibrary != nullptr ? env->GetStringUTFChars(eglLibrary, nullptr) : nullptr;

    void* handle = lodestone::glfw::loadTranslationLayer(pathChars, eglChars);

    if (eglChars != nullptr) {
        env->ReleaseStringUTFChars(eglLibrary, eglChars);
    }
    env->ReleaseStringUTFChars(path, pathChars);
    return handle != nullptr ? JNI_TRUE : JNI_FALSE;
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
        if (s.pendingWindow != nullptr && s.pendingWindow != window) {
            ANativeWindow_release(s.pendingWindow);
        }
        s.pendingWindow = window;
        s.surfaceDirty = true;
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
