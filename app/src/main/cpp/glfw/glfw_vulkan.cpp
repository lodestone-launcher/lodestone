// The GLFW 3.4 Vulkan entry points, implemented on Android's Vulkan loader.
//
// Nothing here translates anything. Minecraft 26.2 ships its own Vulkan renderer, and Android
// exposes Vulkan natively on every device this app runs on, so the game talks to the vendor driver
// directly and the only thing it needs from GLFW is the window-system glue: which instance
// extensions to enable, which queue family can present, and a VkSurfaceKHR for the window. On
// Android those are VK_KHR_android_surface and vkCreateAndroidSurfaceKHR.
//
// All four entry points below are ones the game actually calls, and LWJGL resolves every symbol in
// GLFWVulkan eagerly through `apiGetFunctionAddress`, which throws on a miss — so the two that
// Minecraft never calls have to exist regardless, or the class initialiser takes the launch down
// before the backend is ever tried.

#define VK_NO_PROTOTYPES
#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>

#include <android/native_window.h>
#include <dlfcn.h>

#include <mutex>
#include <vector>

#include "common/log.h"
#include "glfw/glfw_shim.h"

struct GLFWwindow;

namespace {

/**
 * How long `glfwCreateWindowSurface` waits for the activity's window.
 *
 * Long enough to cover a cold start where the VM outruns the surface callback, short enough that a
 * launch with no surface at all still fails while the player is watching rather than appearing to
 * hang.
 */
constexpr int kSurfaceWaitMillis = 5000;

std::mutex g_loaderMutex;
PFN_vkGetInstanceProcAddr g_getInstanceProcAddr = nullptr;
bool g_loaderProbed = false;

/**
 * Android's Vulkan loader entry point, or null when the device has none.
 *
 * `vkGetInstanceProcAddr` is the only symbol taken by name; everything else is resolved through it,
 * which is what the Vulkan loader contract asks for and what keeps this working across vendors
 * whose libvulkan.so exports differ.
 *
 * Note the library name: LWJGL looks for `libvulkan.so.1`, the versioned SONAME Linux
 * distributions use, and Android ships `libvulkan.so` instead. That mismatch is settled on the
 * Java side by pointing `org.lwjgl.vulkan.libname` at the Android name; this is the shim's own
 * copy of the same lookup, and it has to agree.
 */
PFN_vkGetInstanceProcAddr loader() {
    std::lock_guard<std::mutex> lock(g_loaderMutex);
    if (g_loaderProbed) {
        return g_getInstanceProcAddr;
    }
    g_loaderProbed = true;
    if (g_getInstanceProcAddr != nullptr) {
        // Already supplied by glfwInitVulkanLoader.
        return g_getInstanceProcAddr;
    }
    void* library = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (library == nullptr) {
        LOGE("no Vulkan loader: %s", dlerror());
        return nullptr;
    }
    g_getInstanceProcAddr = reinterpret_cast<PFN_vkGetInstanceProcAddr>(
        dlsym(library, "vkGetInstanceProcAddr"));
    if (g_getInstanceProcAddr == nullptr) {
        LOGE("libvulkan.so exports no vkGetInstanceProcAddr");
    }
    return g_getInstanceProcAddr;
}

/** The ANativeWindow the live VkSurfaceKHR was created from, kept alive for as long as it exists. */
ANativeWindow* g_surfaceWindow = nullptr;

} // namespace

extern "C" {

/**
 * Replaces the loader this shim would find on its own.
 *
 * GLFW offers this so an application that has already loaded Vulkan its own way can hand the entry
 * point over rather than have the library open a second copy. Minecraft does not call it, but
 * honouring it is cheap and it is part of the contract LWJGL binds to.
 */
__attribute__((visibility("default"))) void glfwInitVulkanLoader(PFN_vkGetInstanceProcAddr loader) {
    std::lock_guard<std::mutex> lock(g_loaderMutex);
    g_getInstanceProcAddr = loader;
    g_loaderProbed = loader != nullptr;
}

__attribute__((visibility("default"))) int glfwVulkanSupported() {
    return loader() != nullptr ? GLFW_TRUE : GLFW_FALSE;
}

/**
 * The instance extensions a presentable surface needs here.
 *
 * `VK_KHR_surface` and the platform's own surface extension, which on Android is
 * `VK_KHR_android_surface` — the exact counterpart of the xcb, wayland and win32 ones GLFW returns
 * on a desktop. Both are mandatory for any Android driver that supports presentation at all, so
 * this list is the same on every device and does not need probing.
 *
 * The returned array belongs to the shim and outlives the call, as GLFW's contract requires.
 */
__attribute__((visibility("default"))) const char** glfwGetRequiredInstanceExtensions(
        uint32_t* count) {
    static const char* extensions[] = {
        VK_KHR_SURFACE_EXTENSION_NAME,
        VK_KHR_ANDROID_SURFACE_EXTENSION_NAME,
    };
    if (loader() == nullptr) {
        if (count != nullptr) *count = 0;
        return nullptr;
    }
    if (count != nullptr) {
        *count = 2;
    }
    return extensions;
}

__attribute__((visibility("default"))) void (*glfwGetInstanceProcAddress(
        VkInstance instance, const char* procname))(void) {
    PFN_vkGetInstanceProcAddr get = loader();
    if (get == nullptr || procname == nullptr) {
        return nullptr;
    }
    return reinterpret_cast<void (*)(void)>(get(instance, procname));
}

/**
 * Whether [queuefamily] on [device] can present.
 *
 * Android has no `vkGetPhysicalDeviceAndroidPresentationSupportKHR`: presentation to a native
 * window is supported by every queue family that supports graphics, and by no other. That is what
 * this reports, read off the device rather than assumed, so it stays correct on a driver that
 * exposes compute- or transfer-only families.
 *
 * This one matters more than it looks. Minecraft picks the queue it presents on from this answer
 * alone — it never calls `vkGetPhysicalDeviceSurfaceSupportKHR` — so a shim that always said yes
 * would hand it a family that cannot present on some devices and none on others.
 */
__attribute__((visibility("default"))) int glfwGetPhysicalDevicePresentationSupport(
        VkInstance instance, VkPhysicalDevice device, uint32_t queuefamily) {
    PFN_vkGetInstanceProcAddr get = loader();
    if (get == nullptr || device == VK_NULL_HANDLE) {
        return GLFW_FALSE;
    }
    auto getQueueFamilyProperties = reinterpret_cast<PFN_vkGetPhysicalDeviceQueueFamilyProperties>(
        get(instance, "vkGetPhysicalDeviceQueueFamilyProperties"));
    if (getQueueFamilyProperties == nullptr) {
        return GLFW_FALSE;
    }

    uint32_t count = 0;
    getQueueFamilyProperties(device, &count, nullptr);
    if (queuefamily >= count) {
        return GLFW_FALSE;
    }
    std::vector<VkQueueFamilyProperties> families(count);
    getQueueFamilyProperties(device, &count, families.data());
    return (families[queuefamily].queueFlags & VK_QUEUE_GRAPHICS_BIT) != 0 ? GLFW_TRUE : GLFW_FALSE;
}

/**
 * Creates a VkSurfaceKHR for the Android window the activity is showing.
 *
 * The wait is the interesting part. On a desktop the window exists before the game asks for a
 * surface; here the window belongs to the activity, and while the activity does start the VM only
 * once its surface has settled, a launch that races — or one resumed while backgrounded — would
 * otherwise fail outright at the one call the backend cannot retry. Waiting turns that race into a
 * pause, and a genuine absence into `VK_ERROR_INITIALIZATION_FAILED`, which is a failure the game
 * reports as a backend that would not come up rather than as a crash.
 *
 * The reference taken here is held for as long as the surface can be used. The Vulkan spec makes
 * the window the application's to keep valid for the lifetime of the surface it backs, and the
 * driver's swapchain dereferences it on every present.
 */
__attribute__((visibility("default"))) VkResult glfwCreateWindowSurface(
        VkInstance instance, GLFWwindow*, const VkAllocationCallbacks* allocator,
        VkSurfaceKHR* surface) {
    if (surface == nullptr) {
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    PFN_vkGetInstanceProcAddr get = loader();
    if (get == nullptr) {
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    auto createAndroidSurface = reinterpret_cast<PFN_vkCreateAndroidSurfaceKHR>(
        get(instance, "vkCreateAndroidSurfaceKHR"));
    if (createAndroidSurface == nullptr) {
        LOGE("the Vulkan driver has no vkCreateAndroidSurfaceKHR");
        return VK_ERROR_EXTENSION_NOT_PRESENT;
    }

    ANativeWindow* window = lodestone::glfw::acquireWindow(kSurfaceWaitMillis);
    if (window == nullptr) {
        LOGE("no Android window to present to after %d ms", kSurfaceWaitMillis);
        return VK_ERROR_INITIALIZATION_FAILED;
    }

    VkAndroidSurfaceCreateInfoKHR info{};
    info.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    info.window = window;

    const VkResult result = createAndroidSurface(instance, &info, allocator, surface);
    if (result != VK_SUCCESS) {
        LOGE("vkCreateAndroidSurfaceKHR failed: %d", result);
        ANativeWindow_release(window);
        return result;
    }

    if (g_surfaceWindow != nullptr) {
        ANativeWindow_release(g_surfaceWindow);
    }
    g_surfaceWindow = window;
    LOGI("Vulkan surface created on a %dx%d Android window",
         ANativeWindow_getWidth(window), ANativeWindow_getHeight(window));
    return VK_SUCCESS;
}

} // extern "C"
