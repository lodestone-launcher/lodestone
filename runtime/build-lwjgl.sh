#!/usr/bin/env bash
# Cross-compiles the LWJGL natives Minecraft needs, for Android.
#
# The native surface is far smaller than the version manifests suggest. Surveying LWJGL 3.3.3:
#
#   module     C files   how it works on Android
#   ---------  --------  ------------------------------------------------------------------
#   core       5 + 11    real native code; needs libffi. This is the only hard one.
#   stb        1         header-only amalgamation, compiles standalone.
#   opengl     0         pure Java; dlopens the GL driver at runtime -> our gl4es.
#   glfw       0         pure Java; dlopens libglfw -> our liblodestone_glfw.so.
#   openal     0         pure Java; dlopens libopenal -> openal-soft, built separately.
#   jemalloc   0         pure Java; avoidable entirely with -Dorg.lwjgl.system.allocator=system.
#
# So only `core` and `stb` have to be compiled, and the modules that would have been hardest
# (opengl, glfw) need no native build at all — they bind to whatever the launcher points them at:
#
#   -Dorg.lwjgl.glfw.libname=<natives>/liblodestone_glfw.so
#   -Dorg.lwjgl.opengl.libname=<natives>/libgl4es.so
#   -Dorg.lwjgl.system.allocator=system
#
# `core` links against libffi, which LWJGL does not vendor: it ships only `ffitarget.h` per
# architecture and expects a libffi checkout alongside. libffi supports Android upstream, so it is
# built first here.
#
# Usage: build-lwjgl.sh [--version 3.3.3] [--abi arm64-v8a] [--output <dir>] [--ndk <path>]
set -euo pipefail

LWJGL_VERSION="3.3.3"
ABIS=()
OUTPUT="$(pwd)/out/lwjgl"
NDK="${ANDROID_NDK_HOME:-${NDK_HOME:-}}"
API=26
WORK="${TMPDIR:-/tmp}/lodestone-lwjgl"

LIBFFI_REPO="https://github.com/libffi/libffi.git"
LIBFFI_TAG="v3.4.6"
LWJGL_REPO="https://github.com/LWJGL/lwjgl3.git"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --version) LWJGL_VERSION="$2"; shift 2 ;;
        --abi) ABIS+=("$2"); shift 2 ;;
        --output) OUTPUT="$2"; shift 2 ;;
        --ndk) NDK="$2"; shift 2 ;;
        --api) API="$2"; shift 2 ;;
        --work) WORK="$2"; shift 2 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

if [[ ${#ABIS[@]} -eq 0 ]]; then
    ABIS=(arm64-v8a x86_64)
fi
[[ -n "${NDK}" ]] || { echo "Set ANDROID_NDK_HOME or pass --ndk" >&2; exit 2; }

TOOLCHAIN="${NDK}/toolchains/llvm/prebuilt/$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64"
[[ -d "${TOOLCHAIN}" ]] || { echo "No NDK toolchain at ${TOOLCHAIN}" >&2; exit 2; }

mkdir -p "${WORK}" "${OUTPUT}"

# --------------------------------------------------------------------------------------------
# Sources
# --------------------------------------------------------------------------------------------
LWJGL_SRC="${WORK}/lwjgl3"
if [[ ! -d "${LWJGL_SRC}" ]]; then
    echo "==> Cloning LWJGL ${LWJGL_VERSION}"
    git clone --depth 1 --branch "${LWJGL_VERSION}" "${LWJGL_REPO}" "${LWJGL_SRC}"
fi

LIBFFI_SRC="${WORK}/libffi"
if [[ ! -d "${LIBFFI_SRC}" ]]; then
    echo "==> Cloning libffi ${LIBFFI_TAG}"
    git clone --depth 1 --branch "${LIBFFI_TAG}" "${LIBFFI_REPO}" "${LIBFFI_SRC}"
    (cd "${LIBFFI_SRC}" && ./autogen.sh)
fi

triple_for() {
    case "$1" in
        arm64-v8a) echo "aarch64-linux-android" ;;
        x86_64) echo "x86_64-linux-android" ;;
        armeabi-v7a) echo "armv7a-linux-androideabi" ;;
        *) echo "" ;;
    esac
}

for abi in "${ABIS[@]}"; do
    triple="$(triple_for "${abi}")"
    [[ -n "${triple}" ]] || { echo "Unsupported ABI: ${abi}" >&2; exit 2; }

    export CC="${TOOLCHAIN}/bin/${triple}${API}-clang"
    export AR="${TOOLCHAIN}/bin/llvm-ar"
    export RANLIB="${TOOLCHAIN}/bin/llvm-ranlib"

    # ----------------------------------------------------------------------------------------
    # libffi
    # ----------------------------------------------------------------------------------------
    ffi_build="${WORK}/libffi-${abi}"
    ffi_prefix="${WORK}/libffi-install-${abi}"
    if [[ ! -f "${ffi_prefix}/lib/libffi.a" ]]; then
        echo "==> Building libffi for ${abi}"
        rm -rf "${ffi_build}"
        mkdir -p "${ffi_build}"
        (
            cd "${ffi_build}"
            # multi-os-directory probing shells out to `-print-multi-os-directory`, which the NDK's
            # clang rejects outright; the NDK already keeps one sysroot per triple regardless.
            # Static, because the .a is linked straight into liblwjgl.so and shipping a second
            # shared object would only add another file for the loader to find.
            "${LIBFFI_SRC}/configure" \
                --host="${triple}" \
                --prefix="${ffi_prefix}" \
                --enable-static --disable-shared \
                --disable-docs \
                --disable-multi-os-directory
            make -j"$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)"
            make install
        )
    fi

    # ----------------------------------------------------------------------------------------
    # LWJGL core and stb
    # ----------------------------------------------------------------------------------------
    echo "==> Building LWJGL core for ${abi}"
    out="${OUTPUT}/${abi}"
    mkdir -p "${out}"

    core="${LWJGL_SRC}/modules/lwjgl/core/src"
    # LWJGL generates one JNI accessor per libffi calling convention across every architecture it
    # supports, but each `ffitarget.h` defines only its own. On aarch64 that leaves ten undefined.
    # They are separate functions rather than switch cases, so mapping them onto a defined sentinel
    # simply makes them dead code — the aarch64 Java side never asks for an x86 or ARM32 ABI.
    absent_abis=()
    case "${abi}" in
        arm64-v8a)
            for name in FFI_GNUW64 FFI_UNIX64 FFI_EFI64 FFI_STDCALL FFI_THISCALL \
                        FFI_FASTCALL FFI_MS_CDECL FFI_PASCAL FFI_REGISTER FFI_VFP; do
                absent_abis+=("-D${name}=FFI_LAST_ABI")
            done
            ;;
        x86_64)
            # x86_64 keeps FFI_UNIX64/WIN64/GNUW64/EFI64; SYSV is 32-bit only and MS_CDECL is Windows.
            for name in FFI_SYSV FFI_MS_CDECL FFI_STDCALL FFI_THISCALL FFI_FASTCALL \
                        FFI_PASCAL FFI_REGISTER FFI_VFP; do
                absent_abis+=("-D${name}=FFI_LAST_ABI")
            done
            ;;
    esac

    common_flags=(
        -O2 -fPIC -shared -fvisibility=hidden
        -DLWJGL_LINUX -D_GNU_SOURCE
        "${absent_abis[@]}"
        -I"${core}/main/c"
        # `common_tools.h` includes "LinuxConfig.h" unqualified, so the platform directory has to
        # be on the include path in its own right rather than reached through a prefix.
        -I"${core}/main/c/linux"
        -I"${core}/generated/c"
        -I"${core}/generated/c/linux"
        -I"${ffi_prefix}/include"
    )

    # The generated linux sources bind io_uring, which bionic does not ship a userspace library
    # for; Minecraft never touches those paths, so they are left out rather than stubbed.
    # shellcheck disable=SC2046
    "${CC}" "${common_flags[@]}" \
        $(find "${core}/main/c" -maxdepth 1 -name '*.c') \
        $(find "${core}/generated/c" -maxdepth 1 -name '*.c') \
        $(find "${core}/generated/c/linux" -maxdepth 1 -name '*.c' -not -name '*uring*') \
        "${ffi_prefix}/lib/libffi.a" \
        -ldl -llog \
        -o "${out}/liblwjgl.so"

    stb="${LWJGL_SRC}/modules/lwjgl/stb/src"
    # Only the generated bindings are compiled: stb's own amalgamation under main/c is #included
    # by them, so listing it again would define every stb_vorbis symbol twice.
    # stb_dxt.h calls memcpy without including <string.h>, which older compilers tolerated; force it.
    # stb pulls in core's common_tools.h, which only defines DISABLE_WARNINGS/ENABLE_WARNINGS by way
    # of LinuxConfig.h — so this needs the same platform define and include path that core does.
    # shellcheck disable=SC2046
    "${CC}" -O2 -fPIC -shared -fvisibility=hidden \
        -DLWJGL_LINUX -D_GNU_SOURCE \
        -include string.h \
        -I"${core}/main/c" -I"${core}/main/c/linux" -I"${core}/generated/c" -I"${stb}/main/c" \
        $(find "${stb}/generated/c" -maxdepth 1 -name '*.c') \
        -lm -o "${out}/liblwjgl_stb.so"

    echo "    $(ls -la "${out}" | grep -c '\.so') libraries in ${out}"
done

echo "==> LWJGL natives written to ${OUTPUT}"
