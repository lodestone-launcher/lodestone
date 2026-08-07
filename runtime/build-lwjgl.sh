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
            # Static, because the .a is linked straight into liblwjgl.so and shipping a second
            # shared object would only add another file for the loader to find.
            "${LIBFFI_SRC}/configure" \
                --host="${triple}" \
                --prefix="${ffi_prefix}" \
                --enable-static --disable-shared \
                --disable-docs
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
    common_flags=(
        -O2 -fPIC -shared -fvisibility=hidden
        -DLWJGL_LINUX -D_GNU_SOURCE
        -I"${core}/main/c"
        -I"${core}/generated/c"
        -I"${ffi_prefix}/include"
        -I"${TOOLCHAIN}/sysroot/usr/include"
    )

    # shellcheck disable=SC2046
    "${CC}" "${common_flags[@]}" \
        $(find "${core}/main/c" -maxdepth 1 -name '*.c') \
        $(find "${core}/generated/c" -maxdepth 1 -name '*.c') \
        "${ffi_prefix}/lib/libffi.a" \
        -ldl -llog \
        -o "${out}/liblwjgl.so"

    stb="${LWJGL_SRC}/modules/lwjgl/stb/src"
    # shellcheck disable=SC2046
    "${CC}" -O2 -fPIC -shared -fvisibility=hidden \
        -I"${core}/main/c" -I"${core}/generated/c" -I"${stb}/main/c" \
        $(find "${stb}/generated/c" -maxdepth 1 -name '*.c') \
        $(find "${stb}/main/c" -maxdepth 1 -name '*.c') \
        -lm -o "${out}/liblwjgl_stb.so"

    echo "    $(ls -la "${out}" | grep -c '\.so') libraries in ${out}"
done

echo "==> LWJGL natives written to ${OUTPUT}"
