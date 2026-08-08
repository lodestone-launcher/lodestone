#!/usr/bin/env bash
# Fails a runtime image that ships a shared object with a symbol nothing can resolve.
#
# Shared libraries are allowed to leave symbols undefined, and the JDK depends on that: every JNI
# library calls into `libjvm.so` and `libjava.so`, `libfontmanager.so` calls into `libawt.so`, and
# HotSpot's C++ runtime lives in the APK's `libc++_shared.so`. So a blanket `-Wl,--no-undefined`
# over the whole build is wrong. What is never legitimate is an undefined symbol that no library
# present at runtime defines: bionic resolves every relocation eagerly at load time, including
# `R_AARCH64_JUMP_SLOT`, so such a symbol is not a lazy call that might never happen — it is a
# `dlopen` failure the first time the library is opened.
#
# One shipped for real: `ArrayAllocator<bm_word_t, mtInternal>::free()`, an implicit template
# instantiation clang dropped and no other object supplied, which made the whole Java 8 runtime
# unloadable while the build reported success.
#
# Usage: audit-image.sh --image <dir> [--abi arm64-v8a] [--ndk <dir>] [--api <level>]
set -euo pipefail

IMAGE=""
ABI="arm64-v8a"
NDK="${NDK_HOME:-/opt/android-ndk}"
API="${ANDROID_API:-30}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --image) IMAGE="$2"; shift 2 ;;
        --abi) ABI="$2"; shift 2 ;;
        --ndk) NDK="$2"; shift 2 ;;
        --api) API="$2"; shift 2 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

[[ -n "${IMAGE}" ]] || { echo "--image is required" >&2; exit 2; }
[[ -d "${IMAGE}" ]] || { echo "No such image directory: ${IMAGE}" >&2; exit 2; }

case "${ABI}" in
    arm64-v8a) TRIPLE="aarch64-linux-android" ;;
    x86_64)    TRIPLE="x86_64-linux-android" ;;
    *) echo "Unsupported ABI: ${ABI}" >&2; exit 2 ;;
esac

TOOLCHAIN=""
for candidate in "${NDK}"/toolchains/llvm/prebuilt/*; do
    [[ -x "${candidate}/bin/llvm-nm" ]] && TOOLCHAIN="${candidate}"
done
[[ -n "${TOOLCHAIN}" ]] || { echo "No llvm-nm under ${NDK}/toolchains/llvm/prebuilt" >&2; exit 2; }
NM="${TOOLCHAIN}/bin/llvm-nm"
SYSROOT="${TOOLCHAIN}/sysroot"

WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

# ---------------------------------------------------------------------------------------------
# What will be loaded alongside the image
# ---------------------------------------------------------------------------------------------
# The NDK's stub libraries carry the full exported symbol table of the real thing, so they answer
# "does bionic define this" exactly. libc++_shared is not in the image and not in the sysroot's
# per-API directory: the APK ships it, and the library that dlopens libjvm.so links it, so it is
# already loaded under that soname by the time HotSpot is opened.
PLATFORM_LIBS=()
for lib in "${SYSROOT}/usr/lib/${TRIPLE}/${API}"/*.so; do
    [[ -e "${lib}" ]] && PLATFORM_LIBS+=("${lib}")
done
for lib in "${SYSROOT}/usr/lib/${TRIPLE}/libc++_shared.so"; do
    [[ -e "${lib}" ]] && PLATFORM_LIBS+=("${lib}")
done

mapfile -t IMAGE_LIBS < <(find "${IMAGE}" -type f -name '*.so' | sort)
[[ ${#IMAGE_LIBS[@]} -gt 0 ]] || { echo "No shared objects under ${IMAGE}" >&2; exit 2; }

# The version suffix is deliberately stripped from both sides. It records which library the linker
# resolved a symbol against, not the symbol's identity, and the sysroot stub and the device's real
# libc do not always agree on it.
#
# Failure is swallowed rather than propagated because several entries matching `*.so` are not ELF
# at all: the NDK ships `libc++.so` as a linker script, and the build image stages `libpthread.so`
# and `librt.so` as empty ones. A file that defines no symbols is exactly what those should
# contribute here.
defined_symbols() {
    { "${NM}" -D --defined-only --extern-only "$1" 2>/dev/null || true; } \
        | awk '{print $NF}' | sed 's/@.*//'
}

# Weak undefined symbols are legitimate: they are the "is this platform new enough" probes bionic
# is built around, and the loader leaves them at zero rather than failing.
undefined_symbols() {
    { "${NM}" -D --undefined-only "$1" 2>/dev/null || true; } \
        | awk '$1 == "U" {print $NF}' | sed 's/@.*//'
}

for lib in "${IMAGE_LIBS[@]}" "${PLATFORM_LIBS[@]}"; do
    defined_symbols "${lib}"
done | sort -u > "${WORK}/provided"

echo "==> Auditing ${#IMAGE_LIBS[@]} shared objects against ${#PLATFORM_LIBS[@]} platform libraries"

status=0
for lib in "${IMAGE_LIBS[@]}"; do
    undefined_symbols "${lib}" | sort -u > "${WORK}/wanted"
    comm -23 "${WORK}/wanted" "${WORK}/provided" > "${WORK}/missing"
    if [[ -s "${WORK}/missing" ]]; then
        status=1
        echo "!! ${lib#${IMAGE}/}"
        while IFS= read -r symbol; do
            demangled="$("${TOOLCHAIN}/bin/llvm-cxxfilt" "${symbol}" 2>/dev/null || echo "${symbol}")"
            if [[ "${demangled}" == "${symbol}" ]]; then
                echo "     ${symbol}"
            else
                echo "     ${symbol}  (${demangled})"
            fi
        done < "${WORK}/missing"
    fi
done

if [[ ${status} -ne 0 ]]; then
    echo
    echo "Undefined symbols above are defined by nothing that will be loaded. bionic binds every" >&2
    echo "relocation at load time, so each one is a dlopen failure, not a call that might not happen." >&2
    exit 1
fi

echo "==> No unresolvable undefined symbols"
