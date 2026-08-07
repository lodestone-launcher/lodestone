// glibc symbols the JDK's own libraries reference but bionic does not provide.
//
// This lives in its own shared object rather than alongside the bridge because of how Android's
// linker scopes symbols. A library only satisfies another library's undefined symbols if it is in
// that library's own dependency closure or in the namespace's global group, and membership of the
// global group comes from the DF_1_GLOBAL flag in the ELF — `-z global` in CMakeLists, not the
// RTLD_GLOBAL passed to dlopen, which governs dlsym visibility alone. The runtime's libraries are
// dlopened by HotSpot and never name this one, so the global group is the only route to them.

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
