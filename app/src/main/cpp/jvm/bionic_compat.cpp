// glibc symbols the JDK's own libraries reference but bionic does not provide.
//
// This lives in its own shared object rather than alongside the bridge because of how Android's
// linker scopes symbols. ART loads the app's libraries into a local group, and re-opening one with
// RTLD_NOLOAD | RTLD_GLOBAL returns a handle without moving it — so a shim defined in the bridge
// stays invisible to the runtime. Only a library opened fresh with RTLD_GLOBAL joins the global
// group, and only then can libjava.so resolve against it.

#include <cerrno>
#include <cstring>

extern "C" {

/**
 * The XSI flavour of strerror_r.
 *
 * glibc ships both conventions and selects between them by feature macro, exposing the XSI one
 * under this internal name. Because configure is handed the *-linux-gnu triplet, the JDK binds to
 * it; bionic has only the GNU flavour, so libjava.so arrives with an unresolved symbol.
 *
 * The two differ in more than name: GNU returns a pointer that may or may not be the caller's
 * buffer, while XSI always fills the buffer and returns a status. Copying when the pointer differs
 * bridges them.
 */
__attribute__((visibility("default"))) int __xpg_strerror_r(
        int errnum, char* buffer, size_t length) {
    if (buffer == nullptr || length == 0) {
        return ERANGE;
    }
    const char* message = strerror_r(errnum, buffer, length);
    if (message != buffer) {
        std::strncpy(buffer, message, length - 1);
        buffer[length - 1] = '\0';
    }
    return 0;
}

} // extern "C"
