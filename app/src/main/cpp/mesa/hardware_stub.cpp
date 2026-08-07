// Stands in for Android's `libhardware.so`, which Mesa's EGL links for gralloc.
//
// Android's own copy is not on the public library list, so an unprivileged app cannot load it at
// all. Mesa ships an `android_stub` build for exactly this, but its `hw_get_module` reports success
// while leaving the module pointer null — and Mesa's first gralloc backend then dereferences it,
// taking the process down inside `eglInitialize` before Zink has a chance to come up.
//
// Reporting the module as absent is both true here and the answer Mesa already handles: it walks
// its list of gralloc backends and settles on the fallback, which needs no module.

#include <errno.h>

// Explicitly exported: the build hides symbols by default, and Mesa resolves this one by name.
extern "C" __attribute__((visibility("default"))) int hw_get_module(
        const char*, const void** module) {
    if (module != nullptr) {
        *module = nullptr;
    }
    return -ENOSYS;
}
