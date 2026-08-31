// Android's Vulkan loader, with pre-rotation taken out of the picture.
//
// Every Android surface carries a transform describing how the window sits relative to the panel's
// natural orientation: a landscape activity on a portrait phone reports `currentTransform` as a
// 90-degree rotation. The convention is that an application reads that, renders its frame already
// rotated, and passes the same value back as the swapchain's `preTransform` — which lets the
// display controller scan the buffer out untouched.
//
// Minecraft does pass `currentTransform` straight into `preTransform`. On a desktop that is exactly
// right, because there it is always identity and the pass-through is a no-op. On Android it is a
// promise the renderer has not kept: the frame is drawn the right way up, the swapchain says it was
// pre-rotated, and the title screen presents on its side.
//
// Rather than intercept the swapchain and quietly disagree with what the game was told, this
// reports the surface as untransformed. The game then passes identity through, which is true of the
// frame it draws, and the compositor applies the display's own rotation as it does for every other
// app. That costs a rotation the pre-rotated path would have avoided — on hardware that composites
// rotations in the display controller, nothing; on hardware that does not, one pass — and it is the
// only version of this that is honest about what was rendered.
//
// It is a loader rather than a patch because there is nothing to patch: the game is an unmodified
// jar, and LWJGL reaches Vulkan through whatever `org.lwjgl.vulkan.libname` names. Everything below
// forwards; only the surface's transform is answered differently, and nothing here knows or cares
// which vendor's driver is underneath.

#define VK_NO_PROTOTYPES
#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>

#include <dlfcn.h>

#include <atomic>
#include <cstring>
#include <mutex>

#include "common/log.h"

namespace {

std::once_flag g_loaderOnce;
void* g_loader = nullptr;
PFN_vkGetInstanceProcAddr g_getInstanceProcAddr = nullptr;

/**
 * The last instance seen, for resolving the functions this file forwards to.
 *
 * `vkGetInstanceProcAddr` with a null instance answers only the four global commands — everything
 * instance- or device-level comes back null, which is a rule easy to forget and impossible to
 * ignore: the first version of this resolved its forwards that way, and handed VMA a null
 * `vkAllocateMemory`.
 */
std::atomic<VkInstance> g_instance{VK_NULL_HANDLE};

/** Android's real loader, opened once. */
void openLoader() {
    std::call_once(g_loaderOnce, [] {
        g_loader = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
        if (g_loader == nullptr) {
            LOGE("no Vulkan loader to forward to: %s", dlerror());
            return;
        }
        g_getInstanceProcAddr = reinterpret_cast<PFN_vkGetInstanceProcAddr>(
            dlsym(g_loader, "vkGetInstanceProcAddr"));
        if (g_getInstanceProcAddr == nullptr) {
            LOGE("libvulkan.so exports no vkGetInstanceProcAddr");
        }
    });
}

PFN_vkVoidFunction forward(VkInstance instance, const char* name) {
    openLoader();
    if (instance != VK_NULL_HANDLE) {
        g_instance.store(instance);
    }
    return g_getInstanceProcAddr != nullptr ? g_getInstanceProcAddr(instance, name) : nullptr;
}

/**
 * The driver's own implementation of [name].
 *
 * Taken from the loader's symbol table first, which carries every core entry point, and otherwise
 * through an instance — extension functions are not required to be exported as symbols at all.
 * Resolved on each call rather than cached, because the first call may arrive before any instance
 * exists and a cached null would be permanent.
 */
template <typename Function>
Function realProc(const char* name) {
    openLoader();
    if (g_loader != nullptr) {
        if (void* symbol = dlsym(g_loader, name)) {
            return reinterpret_cast<Function>(symbol);
        }
    }
    const VkInstance instance = g_instance.load();
    if (g_getInstanceProcAddr != nullptr && instance != VK_NULL_HANDLE) {
        return reinterpret_cast<Function>(g_getInstanceProcAddr(instance, name));
    }
    return nullptr;
}

/** Whatever the driver would have answered, with the surface's own rotation removed. */
VKAPI_ATTR VkResult VKAPI_CALL interposedGetPhysicalDeviceSurfaceCapabilitiesKHR(
        VkPhysicalDevice device, VkSurfaceKHR surface,
        VkSurfaceCapabilitiesKHR* capabilities) {
    auto real = realProc<PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR>(
        "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");
    if (real == nullptr) {
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    const VkResult result = real(device, surface, capabilities);
    if (result != VK_SUCCESS || capabilities == nullptr) {
        return result;
    }

    // Only claimed when the surface actually offers it, which every Android driver does — but a
    // capability is not the place to be optimistic, and a swapchain asking for a transform the
    // surface does not support is invalid usage rather than a slower path.
    if ((capabilities->supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR) != 0) {
        capabilities->currentTransform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    }
    return result;
}

/**
 * The swapchain, created without the rotation the surface no longer reports.
 *
 * Belt and braces beside the capability above: that one is what makes the game ask for identity,
 * and this is what guarantees it, for a caller that decides its transform some other way.
 */
VKAPI_ATTR VkResult VKAPI_CALL interposedCreateSwapchainKHR(
        VkDevice device, const VkSwapchainCreateInfoKHR* createInfo,
        const VkAllocationCallbacks* allocator, VkSwapchainKHR* swapchain) {
    auto real = realProc<PFN_vkCreateSwapchainKHR>("vkCreateSwapchainKHR");
    if (real == nullptr) {
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    if (createInfo == nullptr) {
        return real(device, createInfo, allocator, swapchain);
    }

    VkSwapchainCreateInfoKHR adjusted = *createInfo;
    if (adjusted.preTransform != VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR) {
        LOGI("swapchain asked for preTransform 0x%x; presenting unrotated instead",
             adjusted.preTransform);
        adjusted.preTransform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    }
    return real(device, &adjusted, allocator, swapchain);
}

VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL interposedGetDeviceProcAddr(
        VkDevice device, const char* name);

/** The one table that decides what is answered by us rather than by the driver. */
PFN_vkVoidFunction interposed(const char* name) {
    if (name == nullptr) {
        return nullptr;
    }
    if (std::strcmp(name, "vkGetPhysicalDeviceSurfaceCapabilitiesKHR") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(
            &interposedGetPhysicalDeviceSurfaceCapabilitiesKHR);
    }
    if (std::strcmp(name, "vkCreateSwapchainKHR") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(&interposedCreateSwapchainKHR);
    }
    if (std::strcmp(name, "vkGetDeviceProcAddr") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(&interposedGetDeviceProcAddr);
    }
    return nullptr;
}

VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL interposedGetDeviceProcAddr(
        VkDevice device, const char* name) {
    // Device-level lookups have to come back through here too: vkCreateSwapchainKHR is a device
    // function, and a caller that resolves it from the device would otherwise walk straight past
    // everything above.
    if (PFN_vkVoidFunction ours = interposed(name)) {
        return ours;
    }
    auto real = realProc<PFN_vkGetDeviceProcAddr>("vkGetDeviceProcAddr");
    return real != nullptr ? real(device, name) : nullptr;
}

} // namespace

extern "C" {

/**
 * The only symbol that has to be found by name.
 *
 * LWJGL opens this library, takes `vkGetInstanceProcAddr` out of it with dlsym, and resolves every
 * other Vulkan function through the result — global commands included, by passing a null instance.
 * So this one entry point is the whole seam.
 */
__attribute__((visibility("default"))) VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL
vkGetInstanceProcAddr(VkInstance instance, const char* name) {
    if (name != nullptr && std::strcmp(name, "vkGetInstanceProcAddr") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(&vkGetInstanceProcAddr);
    }
    if (PFN_vkVoidFunction ours = interposed(name)) {
        return ours;
    }
    return forward(instance, name);
}

} // extern "C"
