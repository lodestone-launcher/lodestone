#!/usr/bin/env bash
# Cross-compiles the LWJGL natives Minecraft needs, for Android.
#
# Mojang's manifests carry Linux natives for x86-64 only, so every one of them has to be rebuilt
# here and substituted over the extracted copies. What a version actually unpacks is one `.so` per
# natives jar, and those fall into two kinds. A binding that goes through `Library.loadSystem`
# ships generated JNI stubs and needs a `liblwjgl_<module>.so`; a binding that goes through
# `Library.loadNative` has no native code of its own at all — it dispatches through core's libffi
# and the jar ships the third-party library instead. The table below is read back off the published
# `-natives-linux` jars, which is the only statement of it that cannot drift:
#
#   natives jar        .so it unpacks          C files  built here from
#   -----------------  ----------------------  -------  ----------------------------------------
#   lwjgl              liblwjgl.so              5 + 11   core; needs libffi. The only hard one.
#   lwjgl-opengl       liblwjgl_opengl.so          184   one stub per GL version and extension.
#   lwjgl-stb          liblwjgl_stb.so              11   amalgamation, compiles standalone.
#   lwjgl-tinyfd       liblwjgl_tinyfd.so        1 + 1   stubs plus tinyfiledialogs itself.
#   lwjgl-freetype     libfreetype.so                -   FreeType upstream, via CMake.
#   lwjgl-openal       libopenal.so                  -   OpenAL Soft upstream, via CMake.
#   lwjgl-glfw         libglfw.so                    -   not built: our own shim stands in for it.
#   lwjgl-jemalloc     libjemalloc.so                -   not built, see below.
#   com.mojang:jtracy  libjtracy-jni-linux.so        -   not built, see below.
#
# The first four are generated from the bindings of one exact LWJGL release and only ever match
# that release's Java side: 26.2 moves to 3.4.1, whose `LibFFI` class calls an
# `ffi_get_closure_size` that does not exist in 3.3.3's core, and the launch dies at the first
# `GLFWErrorCallback.create`. So they are built once per LWJGL version Lodestone supports and
# `--output` keeps the sets apart. The last two are upstream projects the bindings reach through
# libffi by symbol name, with no generated code and no version coupling, so one build of each
# serves every set; `--third-party no` skips them when adding a set.
#
# jemalloc is skipped because `MemoryManage.getInstance` catches the failure to instantiate
# `JEmallocAllocator` and falls back to the stdlib allocator; the launcher also asks for that
# outright. jtracy is skipped because `TracyClient.load` is reached only from the `--tracy`
# command-line option, so its library is never dlopened during a normal launch.
#
# The launcher points the remaining bindings at what they should open instead:
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
#                       [--third-party yes|no] [--libffi <tag>]
set -euo pipefail

LWJGL_VERSION="3.3.3"
ABIS=()
OUTPUT=""
THIRD_PARTY="yes"
NDK="${ANDROID_NDK_HOME:-${NDK_HOME:-}}"
API=26
WORK="${TMPDIR:-/tmp}/lodestone-lwjgl"

LIBFFI_REPO="https://github.com/libffi/libffi.git"
LIBFFI_TAG=""
LWJGL_REPO="https://github.com/LWJGL/lwjgl3.git"
# LWJGL builds these two from untagged commits a few days either side of a release. The nearest
# tags are used instead, which for OpenAL Soft gives byte-identical coverage of the entry points
# Mojang's own libopenal.so exports.
FREETYPE_REPO="https://github.com/freetype/freetype.git"
FREETYPE_TAG="VER-2-13-2"
OPENAL_REPO="https://github.com/kcat/openal-soft.git"
OPENAL_TAG="1.23.1"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --version) LWJGL_VERSION="$2"; shift 2 ;;
        --abi) ABIS+=("$2"); shift 2 ;;
        --output) OUTPUT="$2"; shift 2 ;;
        --third-party) THIRD_PARTY="$2"; shift 2 ;;
        --libffi) LIBFFI_TAG="$2"; shift 2 ;;
        --ndk) NDK="$2"; shift 2 ;;
        --api) API="$2"; shift 2 ;;
        --work) WORK="$2"; shift 2 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

if [[ ${#ABIS[@]} -eq 0 ]]; then
    ABIS=(arm64-v8a x86_64)
fi
# Defaulted after parsing so that the version reaches the path: two sets written to one directory
# would leave whichever ran last standing in for both.
OUTPUT="${OUTPUT:-$(pwd)/out/lwjgl/${LWJGL_VERSION}}"

# core's generated `LibFFI.c` calls whatever libffi the release was generated against, so the
# checkout has to track it: 3.4.0's notes record the move to libffi 3.5.2, which is where
# `ffi_get_version`, `ffi_get_version_number`, `ffi_get_default_abi` and `ffi_get_closure_size`
# first appear. Building 3.4.x against 3.4.6 fails outright on those four; building 3.3.3 against
# 3.5.2 would link but is left alone, since that set is already shipped and verified.
if [[ -z "${LIBFFI_TAG}" ]]; then
    case "${LWJGL_VERSION}" in
        3.3.*) LIBFFI_TAG="v3.4.6" ;;
        *) LIBFFI_TAG="v3.5.2" ;;
    esac
fi
[[ -n "${NDK}" ]] || { echo "Set ANDROID_NDK_HOME or pass --ndk" >&2; exit 2; }

TOOLCHAIN="${NDK}/toolchains/llvm/prebuilt/$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64"
[[ -d "${TOOLCHAIN}" ]] || { echo "No NDK toolchain at ${TOOLCHAIN}" >&2; exit 2; }

TOOLCHAIN_FILE="${NDK}/build/cmake/android.toolchain.cmake"
[[ -f "${TOOLCHAIN_FILE}" ]] || { echo "No CMake toolchain at ${TOOLCHAIN_FILE}" >&2; exit 2; }

READELF="${TOOLCHAIN}/bin/llvm-readelf"
STRIP="${TOOLCHAIN}/bin/llvm-strip"

mkdir -p "${WORK}" "${OUTPUT}"

# --------------------------------------------------------------------------------------------
# Sources
# --------------------------------------------------------------------------------------------
# Per version, so that a second set does not quietly compile from the first one's checkout.
LWJGL_SRC="${WORK}/lwjgl3-${LWJGL_VERSION}"
if [[ ! -d "${LWJGL_SRC}" ]]; then
    echo "==> Cloning LWJGL ${LWJGL_VERSION}"
    git clone --depth 1 --branch "${LWJGL_VERSION}" "${LWJGL_REPO}" "${LWJGL_SRC}"
fi

LIBFFI_SRC="${WORK}/libffi-${LIBFFI_TAG}"
if [[ ! -d "${LIBFFI_SRC}" ]]; then
    echo "==> Cloning libffi ${LIBFFI_TAG}"
    git clone --depth 1 --branch "${LIBFFI_TAG}" "${LIBFFI_REPO}" "${LIBFFI_SRC}"
    (cd "${LIBFFI_SRC}" && ./autogen.sh)
fi

FREETYPE_SRC="${WORK}/freetype"
if [[ "${THIRD_PARTY}" == "yes" && ! -d "${FREETYPE_SRC}" ]]; then
    echo "==> Cloning FreeType ${FREETYPE_TAG}"
    git clone --depth 1 --branch "${FREETYPE_TAG}" "${FREETYPE_REPO}" "${FREETYPE_SRC}"
fi

OPENAL_SRC="${WORK}/openal-soft"
if [[ "${THIRD_PARTY}" == "yes" && ! -d "${OPENAL_SRC}" ]]; then
    echo "==> Cloning OpenAL Soft ${OPENAL_TAG}"
    git clone --depth 1 --branch "${OPENAL_TAG}" "${OPENAL_REPO}" "${OPENAL_SRC}"
fi

triple_for() {
    case "$1" in
        arm64-v8a) echo "aarch64-linux-android" ;;
        x86_64) echo "x86_64-linux-android" ;;
        armeabi-v7a) echo "armv7a-linux-androideabi" ;;
        *) echo "" ;;
    esac
}

machine_for() {
    case "$1" in
        arm64-v8a) echo "AArch64" ;;
        x86_64) echo "Advanced Micro Devices X86-64" ;;
        armeabi-v7a) echo "ARM" ;;
        *) echo "" ;;
    esac
}

# A library built for the wrong architecture links, installs and packages perfectly happily, and
# only fails at `dlopen` inside the JVM on a device. Read the machine type back off every artifact
# rather than trusting that the toolchain was the one it was asked for.
verify() {
    local lib="$1" want="$2" kind="$3" machine exports
    machine="$("${READELF}" -h "${lib}" | sed -n 's/^ *Machine: *//p')"
    [[ "${machine}" == "${want}" ]] \
        || { echo "${lib}: built for ${machine}, expected ${want}" >&2; exit 1; }

    if [[ "${kind}" == "jni" ]]; then
        exports="$("${READELF}" --dyn-syms "${lib}" | grep -c ' Java_' || true)"
        [[ "${exports}" -gt 0 ]] \
            || { echo "${lib}: no Java_ exports, nothing would bind to it" >&2; exit 1; }
        printf '    %-24s %-30s %5s JNI exports\n' "$(basename "${lib}")" "${machine}" "${exports}"
    else
        printf '    %-24s %-30s\n' "$(basename "${lib}")" "${machine}"
    fi
}

for abi in "${ABIS[@]}"; do
    triple="$(triple_for "${abi}")"
    [[ -n "${triple}" ]] || { echo "Unsupported ABI: ${abi}" >&2; exit 2; }
    machine="$(machine_for "${abi}")"

    export CC="${TOOLCHAIN}/bin/${triple}${API}-clang"
    export AR="${TOOLCHAIN}/bin/llvm-ar"
    export RANLIB="${TOOLCHAIN}/bin/llvm-ranlib"

    out="${OUTPUT}/${abi}"
    mkdir -p "${out}"

    # ----------------------------------------------------------------------------------------
    # libffi
    # ----------------------------------------------------------------------------------------
    ffi_build="${WORK}/libffi-${LIBFFI_TAG}-${abi}"
    ffi_prefix="${WORK}/libffi-install-${LIBFFI_TAG}-${abi}"
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
    # LWJGL JNI libraries
    # ----------------------------------------------------------------------------------------
    echo "==> Building LWJGL core for ${abi}"

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

    # Every binding's generated C reaches core's `common_tools.h`, which includes "LinuxConfig.h"
    # unqualified — so the platform directory has to be on the include path in its own right
    # rather than reached through a prefix, for the bindings just as much as for core itself.
    jni_flags=(
        -O2 -fPIC -shared -fvisibility=hidden
        -DLWJGL_LINUX -D_GNU_SOURCE
        -I"${core}/main/c"
        -I"${core}/main/c/linux"
        -I"${core}/generated/c"
    )

    # The generated linux sources bind io_uring, which bionic does not ship a userspace library
    # for; Minecraft never touches those paths, so they are left out rather than stubbed.
    # shellcheck disable=SC2046
    "${CC}" "${jni_flags[@]}" "${absent_abis[@]}" \
        -I"${core}/generated/c/linux" \
        -I"${ffi_prefix}/include" \
        $(find "${core}/main/c" -maxdepth 1 -name '*.c') \
        $(find "${core}/generated/c" -maxdepth 1 -name '*.c') \
        $(find "${core}/generated/c/linux" -maxdepth 1 -name '*.c' -not -name '*uring*') \
        "${ffi_prefix}/lib/libffi.a" \
        -ldl -llog \
        -o "${out}/liblwjgl.so"

    echo "==> Building LWJGL opengl for ${abi}"
    opengl="${LWJGL_SRC}/modules/lwjgl/opengl/src"
    # WGL and GLX are the Windows and X11 context-creation extensions. Neither has anything to
    # bind to here, and the GLFW shim owns context creation anyway.
    # shellcheck disable=SC2046
    "${CC}" "${jni_flags[@]}" \
        -I"${opengl}/main/c" \
        $(find "${opengl}/generated/c" -maxdepth 1 -name '*.c' -not -name '*WGL*' -not -name '*GLX*') \
        -ldl -o "${out}/liblwjgl_opengl.so"

    echo "==> Building LWJGL stb for ${abi}"
    stb="${LWJGL_SRC}/modules/lwjgl/stb/src"
    # Only the generated bindings are compiled: stb's own amalgamation under main/c is #included
    # by them, so listing it again would define every stb_vorbis symbol twice.
    # stb_dxt.h calls memcpy without including <string.h>, which older compilers tolerated; force it.
    # shellcheck disable=SC2046
    "${CC}" "${jni_flags[@]}" \
        -include string.h \
        -I"${stb}/main/c" \
        $(find "${stb}/generated/c" -maxdepth 1 -name '*.c') \
        -lm -o "${out}/liblwjgl_stb.so"

    echo "==> Building LWJGL tinyfd for ${abi}"
    tinyfd="${LWJGL_SRC}/modules/lwjgl/tinyfd/src"
    # tinyfiledialogs shells out to zenity, kdialog and friends, none of which exist on Android, so
    # every dialog it offers will decline at runtime. It is still built because Minecraft reaches
    # for `tinyfd_messageBox` on its crash paths, and a missing library there would replace the
    # crash report with an UnsatisfiedLinkError.
    "${CC}" "${jni_flags[@]}" \
        -I"${tinyfd}/main/c" \
        "${tinyfd}/generated/c/org_lwjgl_util_tinyfd_TinyFileDialogs.c" \
        "${tinyfd}/main/c/tinyfiledialogs.c" \
        -o "${out}/liblwjgl_tinyfd.so"

    # ----------------------------------------------------------------------------------------
    # The libraries the libffi-dispatched bindings open
    # ----------------------------------------------------------------------------------------
    if [[ "${THIRD_PARTY}" == "yes" ]]; then
        # Both projects still declare `cmake_minimum_required(VERSION 3.0)`, which CMake 4 refuses
        # outright; the compatibility floor is what lets a current CMake configure them at all.
        cmake_flags=(
            -G Ninja
            -DCMAKE_TOOLCHAIN_FILE="${TOOLCHAIN_FILE}"
            -DCMAKE_POLICY_VERSION_MINIMUM=3.5
            -DANDROID_ABI="${abi}"
            -DANDROID_PLATFORM="android-${API}"
            -DCMAKE_BUILD_TYPE=Release
        )

        echo "==> Building FreeType for ${abi}"
        ft_build="${WORK}/freetype-${abi}"
        # Upstream LWJGL bundles HarfBuzz inside its libfreetype.so, but the freetype binding
        # resolves no `hb_` symbol — HarfBuzz is a separate module Minecraft does not ship — so it
        # is left out along with the other optional codecs, which only exist to decode formats
        # fonts do not use.
        cmake -S "${FREETYPE_SRC}" -B "${ft_build}" "${cmake_flags[@]}" \
            -DBUILD_SHARED_LIBS=ON \
            -DFT_DISABLE_HARFBUZZ=ON \
            -DFT_DISABLE_BROTLI=ON \
            -DFT_DISABLE_BZIP2=ON \
            -DFT_DISABLE_PNG=ON \
            -DFT_DISABLE_ZLIB=ON
        cmake --build "${ft_build}"
        cp "${ft_build}/libfreetype.so" "${out}/libfreetype.so"

        echo "==> Building OpenAL Soft for ${abi}"
        al_build="${WORK}/openal-soft-${abi}"
        # OpenSL ES is the only output path bionic offers; ALSA, PulseAudio and PipeWire are all
        # absent. The STL is linked statically because this library is dropped on its own into the
        # version's natives directory, and a libc++_shared.so dependency there would have nothing
        # to resolve to.
        cmake -S "${OPENAL_SRC}" -B "${al_build}" "${cmake_flags[@]}" \
            -DANDROID_STL=c++_static \
            -DLIBTYPE=SHARED \
            -DALSOFT_BACKEND_OPENSL=ON \
            -DALSOFT_UTILS=OFF \
            -DALSOFT_EXAMPLES=OFF \
            -DALSOFT_INSTALL=OFF
        cmake --build "${al_build}"
        cp "${al_build}/libopenal.so" "${out}/libopenal.so"
    fi

    # ----------------------------------------------------------------------------------------
    # Verification
    # ----------------------------------------------------------------------------------------
    # The JNI sets are packaged as assets, which the Android plugin copies verbatim rather than
    # stripping the way it does everything under `lib/`. Nothing here is reached except through the
    # dynamic symbol table, which stripping leaves alone, so the rest is a quarter of the set's size
    # spent on symbols no loader will ever read.
    for lib in "${out}"/*.so; do
        "${STRIP}" "${lib}"
    done

    echo "==> Verifying ${abi}"
    for lib in liblwjgl liblwjgl_opengl liblwjgl_stb liblwjgl_tinyfd; do
        verify "${out}/${lib}.so" "${machine}" jni
    done
    if [[ "${THIRD_PARTY}" == "yes" ]]; then
        for lib in libfreetype libopenal; do
            verify "${out}/${lib}.so" "${machine}" plain
        done
    fi
done

echo "==> LWJGL natives written to ${OUTPUT}"
