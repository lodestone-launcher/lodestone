#!/usr/bin/env bash
# Cross-compiles gl4es, the desktop-OpenGL-to-GLES translation layer.
#
# Minecraft calls desktop OpenGL, which Android does not have at all. gl4es exports the desktop
# entry points and rewrites them onto GLES underneath, which is what lets an unmodified game jar
# render on a phone. The GLFW shim `dlopen`s the result and resolves `glfwGetProcAddress` against it.
#
# Scope: gl4es covers OpenGL 2.1 with parts of 3.x, which is enough for Minecraft up to 1.16.5.
# Versions from 1.17 onwards require a 3.2 core profile and need Zink over Vulkan instead.
#
# Usage: build-gl4es.sh [--abi arm64-v8a] [--output <dir>] [--ndk <path>] [--api 26]
set -euo pipefail

ABIS=()
OUTPUT="$(pwd)/out/gl4es"
NDK="${ANDROID_NDK_HOME:-${NDK_HOME:-}}"
API=26
# Our fork, because gl4es upstream has no OpenGL 3.x at all: every version from 1.17 asks for a
# 3.3 core profile, and what stood in the way was not emulation but missing wrappers — GL ES 3.0
# already implements uniform buffers and sync objects, with the same names and signatures. The
# branch carries those and the profile-mask reporting; nothing on it is device-specific.
REPOSITORY="https://github.com/lodestone-launcher/gl4es.git"
BRANCH="lodestone/gl-33-core"
WORK="${TMPDIR:-/tmp}/lodestone-gl4es"

while [[ $# -gt 0 ]]; do
    case "$1" in
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
[[ -d "${NDK}" ]] || { echo "No NDK at ${NDK}" >&2; exit 2; }

TOOLCHAIN_FILE="${NDK}/build/cmake/android.toolchain.cmake"
[[ -f "${TOOLCHAIN_FILE}" ]] || { echo "No CMake toolchain at ${TOOLCHAIN_FILE}" >&2; exit 2; }

SOURCE="${WORK}/gl4es"
if [[ ! -d "${SOURCE}" ]]; then
    echo "==> Cloning gl4es"
    mkdir -p "${WORK}"
    git clone --depth 1 --branch "${BRANCH}" "${REPOSITORY}" "${SOURCE}"
fi

mkdir -p "${OUTPUT}"

for abi in "${ABIS[@]}"; do
    echo "==> Building gl4es for ${abi}"
    build="${SOURCE}/build-${abi}"
    rm -rf "${build}" "${SOURCE}/lib"
    mkdir -p "${build}"

    # NOX11 because there is no X server, and EGL_WRAPPER off because the GLFW shim creates and
    # owns the EGL context itself — gl4es is only needed for the GL entry points. Leaving the
    # wrapper on also fails to link, since it depends on the GLX stubs NOX11 removes.
    cmake -S "${SOURCE}" -B "${build}" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="${TOOLCHAIN_FILE}" \
        -DANDROID_ABI="${abi}" \
        -DANDROID_PLATFORM="android-${API}" \
        -DANDROID=ON \
        -DUSE_ANDROID_LOG=ON \
        -DNOX11=ON \
        -DEGL_WRAPPER=OFF \
        -DGLX_STUBS=OFF \
        -DCMAKE_BUILD_TYPE=Release

    cmake --build "${build}"

    mkdir -p "${OUTPUT}/${abi}"
    # Renamed off gl4es's `libGL.so.1`: Android's packager only accepts `lib*.so`, and the shim
    # must open our copy rather than anything the system happens to expose under the GL name.
    cp "${SOURCE}/lib/libGL.so.1" "${OUTPUT}/${abi}/libgl4es.so"
    echo "    $(ls -la "${OUTPUT}/${abi}/libgl4es.so" | awk '{print $5}') bytes"
done

echo "==> gl4es written to ${OUTPUT}"
