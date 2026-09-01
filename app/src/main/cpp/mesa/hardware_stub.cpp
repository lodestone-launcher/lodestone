// Stands in for Android's `libhardware.so`, which Mesa's EGL links for gralloc.
//
// Android's own copy is not on the public library list — the loader's LLNDK set carries libEGL,
// libGLESv*, libvulkan and friends, and no libhardware — so an unprivileged app cannot load it at
// all. Mesa ships an `android_stub` build for exactly this case, and that stub is wrong: its
// `hw_get_module` returns 0 for success and never writes through `module`, leaving the caller's
// pointer as `CALLOC_STRUCT` left it, which is null.
//
// `u_gralloc_fallback_create` then reads that as success and goes straight to
// `gr->gralloc_module->lock_ycbcr`, which is a null dereference at the offset of that field —
// observed here as a SIGSEGV at 0x10 inside `eglInitialize`, before Zink was ever reached.
//
// Reporting the module as absent is both true and the branch Mesa already writes for: the same
// function answers a non-zero return with `mesa_logw("No gralloc hwmodule detected (video buffers
// won't be supported)")` and carries on. That warning is the whole cost, and it is bounded to
// importing YCbCr video buffers, which this app never asks anything to do.

#include <errno.h>

// Explicitly exported: the build hides symbols by default, and Mesa resolves this one by name.
extern "C" __attribute__((visibility("default"))) int hw_get_module(
        const char*, const void** module) {
    if (module != nullptr) {
        *module = nullptr;
    }
    return -ENOSYS;
}
