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
#   lwjgl-vma          liblwjgl_vma.so           2 + 1   stubs plus the header-only allocator.
#   lwjgl-shaderc      libshaderc.so                 -   google/shaderc upstream, via CMake.
#   lwjgl-spvc         libspirv-cross.so             -   SPIRV-Cross upstream, via CMake.
#
# Those last two keep upstream's own file names here — libshaderc_shared.so and
# libspirv-cross-c-shared.so — rather than the ones LWJGL looks for by default, so that each
# file agrees with the SONAME the loader records it under. The launcher points
# `org.lwjgl.shaderc.libname` and `org.lwjgl.spvc.libname` at them.
#   lwjgl-vulkan       -                             -   nothing to build; Android has a loader.
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
# The last four are what Minecraft 26.2's Vulkan backend needs, and they are built only for the
# sets that serve a version carrying one (`--vulkan`, which defaults on from 3.4). vma divides like
# the first group — generated stubs, coupled to its release — while shaderc and spvc divide like
# FreeType and OpenAL, reached through libffi by symbol name. lwjgl-vulkan is the exception that
# needs nothing at all: its natives jar carries only MoltenVK for macOS, and everywhere else the
# bindings open the system loader. On Android that loader is `libvulkan.so`, where LWJGL looks for
# the versioned `libvulkan.so.1` a Linux distribution ships, so the launcher sets
# `-Dorg.lwjgl.vulkan.libname` rather than anything being built here.
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
#   -Dorg.lwjgl.vulkan.libname=libvulkan.so
#   -Dorg.lwjgl.system.allocator=system
#
# `core` links against libffi, which LWJGL does not vendor: it ships only `ffitarget.h` per
# architecture and expects a libffi checkout alongside. libffi supports Android upstream, so it is
# built first here.
#
# `--lwjgl2 yes` emits one more artifact, `<abi>/lwjgl2/liblwjgl_opengl.so`, for the LWJGL 2
# compatibility layer. That layer has to declare `org.lwjgl.opengl.GL11` itself, which hides
# LWJGL 3's class of the same name — 243 of the two APIs' class names collide — so the shim jar
# carries LWJGL 3's opengl bindings relocated into a package of ours. Relocating the Java side
# renames the JNI symbols the bindings resolve against, and those are compiled here, so the rename
# is applied to the generated sources before they are compiled a second time. Renaming the built
# library instead does not work: `llvm-objcopy --redefine-syms` rewrites `.symtab` and leaves
# `.dynsym`, which is the table JNI resolves through, untouched.
#
# Usage: build-lwjgl.sh [--version 3.3.3] [--abi arm64-v8a] [--output <dir>] [--ndk <path>]
#                       [--third-party yes|no] [--lwjgl2 yes|no] [--vulkan yes|no|auto]
#                       [--libffi <tag>]
set -euo pipefail

LWJGL_VERSION="3.3.3"
ABIS=()
OUTPUT=""
THIRD_PARTY="yes"
LWJGL2="no"
VULKAN="auto"
# Kept in step with the `relocate` call in app/build.gradle.kts by
# LwjglRelocationTest, which reads the prefix back out of both files.
LWJGL2_PACKAGE="com.github.lodestone.lwjgl3"
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
# The two libraries Minecraft's Vulkan backend compiles its shaders through. Neither has generated
# JNI code: the bindings reach them through libffi by symbol name, so what matters is that the C
# API is present and the SONAME is the one LWJGL asks for, not that the build matches a release.
# Pinned to the release LWJGL built its own bindings against, not to whatever is newest. The
# manifest names `org.lwjgl:lwjgl-shaderc:3.4.1`, which fixes the *binding* version; the upstream
# release that binding expects is not named anywhere in the manifest, and has to come from LWJGL.
#
# Read it back off LWJGL's own published binary, which is the only statement of it that cannot
# drift:
#
#   unzip -p lwjgl-shaderc-<version>-natives-linux.jar linux/x64/org/lwjgl/shaderc/libshaderc.so \
#       | strings | grep 'SPIRV-Tools v'
#
# For 3.4.1 that reports `SPIRV-Tools v2026.1`, and shaderc pins its SPIRV-Tools through
# git-sync-deps, so v2026.1 is the matching shaderc. Guessing instead is how this first landed on
# v2025.2, which predates shaderc_compile_options_set_max_id_bound: the library loaded cleanly and
# then threw a missing-function NullPointerException four frames below VulkanBackend.createDevice.
# The entry-point check at the end of this script is what now catches that at build time.
SHADERC_REPO="https://github.com/google/shaderc.git"
SHADERC_TAG="v2026.1"
SPIRV_CROSS_REPO="https://github.com/KhronosGroup/SPIRV-Cross.git"
SPIRV_CROSS_TAG="vulkan-sdk-1.3.290.0"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --version) LWJGL_VERSION="$2"; shift 2 ;;
        --abi) ABIS+=("$2"); shift 2 ;;
        --output) OUTPUT="$2"; shift 2 ;;
        --third-party) THIRD_PARTY="$2"; shift 2 ;;
        --vulkan) VULKAN="$2"; shift 2 ;;
        --lwjgl2) LWJGL2="$2"; shift 2 ;;
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

# Minecraft's Vulkan backend arrived with LWJGL 3.4, and only a set that carries vma, shaderc and
# spvc can serve it. Older sets are left alone: building three more libraries for a version whose
# game has no Vulkan renderer to use them would only make the set bigger.
if [[ "${VULKAN}" == "auto" ]]; then
    case "${LWJGL_VERSION}" in
        3.[0-3].*) VULKAN="no" ;;
        *) VULKAN="yes" ;;
    esac
fi

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

SHADERC_SRC="${WORK}/shaderc"
if [[ "${VULKAN}" == "yes" && ! -d "${SHADERC_SRC}" ]]; then
    echo "==> Cloning shaderc ${SHADERC_TAG}"
    git clone --depth 1 --branch "${SHADERC_TAG}" "${SHADERC_REPO}" "${SHADERC_SRC}"
    # shaderc keeps glslang and SPIRV-Tools out of tree and pins them by commit here rather than as
    # submodules; this is the only supported way to get the revisions it compiles against.
    (cd "${SHADERC_SRC}" && ./utils/git-sync-deps)
fi

SPIRV_CROSS_SRC="${WORK}/SPIRV-Cross"
if [[ "${VULKAN}" == "yes" && ! -d "${SPIRV_CROSS_SRC}" ]]; then
    echo "==> Cloning SPIRV-Cross ${SPIRV_CROSS_TAG}"
    git clone --depth 1 --branch "${SPIRV_CROSS_TAG}" "${SPIRV_CROSS_REPO}" "${SPIRV_CROSS_SRC}"
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
# grep exits 1 when it matches nothing, which under `set -e` would end the run at precisely the
# point where a rename had succeeded. Counted through here so that no matches reads as a zero.
count_in() {
    local pattern="$1"
    shift
    grep -ho "${pattern}" "$@" | wc -l | tr -d ' ' || true
}

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
        # NDEBUG because these are release builds of libraries whose assertions are aimed at
        # whoever integrates them, not at whoever compiles them, and LWJGL's own published natives
        # define it. Without it the Vulkan Memory Allocator aborts the process when Minecraft
        # destroys a buffer it deliberately left persistently mapped — a pattern the allocator
        # supports and only a debug build objects to.
        -O2 -DNDEBUG -fPIC -shared -fvisibility=hidden
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

    if [[ "${LWJGL2}" == "yes" ]]; then
        echo "==> Building LWJGL opengl for ${abi}, relocated for the LWJGL 2 layer"
        # Every `org_lwjgl` in these sources is part of a JNI export name and nothing else — the
        # count below is asserted against the bindings' native-method count, so a release that
        # started mentioning the package for some other reason would stop the build rather than
        # silently rename something it should not.
        relocated="${WORK}/opengl-lwjgl2-${LWJGL_VERSION}/c"
        rm -rf "${relocated}"
        mkdir -p "${relocated}"
        cp "${opengl}"/generated/c/*.c "${relocated}/"
        rm -f "${relocated}"/*WGL* "${relocated}"/*GLX*
        mangled="Java_$(echo "${LWJGL2_PACKAGE}" | tr '.' '_')_opengl_"
        renamed="$(count_in 'Java_org_lwjgl_opengl_' "${relocated}"/*.c)"
        mentions="$(count_in 'org_lwjgl[A-Za-z0-9_]*' "${relocated}"/*.c)"
        [[ "${mentions}" -eq "${renamed}" ]] \
            || { echo "opengl sources mention org_lwjgl outside a JNI export name" >&2; exit 1; }
        # BSD sed wants the backup suffix as its own word and GNU sed refuses one; try both.
        sed -i '' -e "s/Java_org_lwjgl_opengl_/${mangled}/g" "${relocated}"/*.c 2>/dev/null \
            || sed -i -e "s/Java_org_lwjgl_opengl_/${mangled}/g" "${relocated}"/*.c
        left="$(count_in 'Java_org_lwjgl_opengl_' "${relocated}"/*.c)"
        [[ "${left}" -eq 0 ]] \
            || { echo "${left} JNI export names were not relocated" >&2; exit 1; }
        mkdir -p "${out}/lwjgl2"
        # shellcheck disable=SC2046
        "${CC}" "${jni_flags[@]}" \
            -I"${opengl}/main/c" \
            $(find "${relocated}" -maxdepth 1 -name '*.c') \
            -ldl -o "${out}/lwjgl2/liblwjgl_opengl.so"
    fi

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
    # The Vulkan backend
    # ----------------------------------------------------------------------------------------
    # Minecraft 26.2 renders through its own Vulkan backend, which on Android is the only path that
    # reaches the GPU without a translation layer in the way. It needs three libraries beyond the
    # set above, and they divide the same way everything else here does: vma has generated JNI
    # stubs and is version-coupled, while shaderc and spvc are upstream projects the bindings reach
    # through libffi by symbol name.
    #
    # Vulkan itself is not built or shipped. It is a system library on every Android device, and the
    # launcher points `org.lwjgl.vulkan.libname` at the name Android uses — `libvulkan.so`, where
    # LWJGL would otherwise look for the versioned `libvulkan.so.1` that Linux distributions ship.
    if [[ "${VULKAN}" == "yes" ]]; then
        echo "==> Building LWJGL vma for ${abi}"
        vma="${LWJGL_SRC}/modules/lwjgl/vma/src"
        # C++ rather than C: the Vulkan Memory Allocator is a C++ header-only library, and the
        # generated bindings include its implementation. Static libc++ because this library is
        # dropped on its own into the version's natives directory, where a libc++_shared.so
        # dependency would have nothing to resolve against.
        "${TOOLCHAIN}/bin/${triple}${API}-clang++" \
            -O2 -DNDEBUG -fPIC -shared -fvisibility=hidden \
            -DLWJGL_LINUX -D_GNU_SOURCE \
            -static-libstdc++ \
            -I"${core}/main/c" \
            -I"${core}/main/c/linux" \
            -I"${core}/generated/c" \
            -I"${vma}/main/c" \
            "${vma}/generated/c/org_lwjgl_util_vma_LibVma.cpp" \
            "${vma}/generated/c/org_lwjgl_util_vma_Vma.cpp" \
            -ldl -o "${out}/liblwjgl_vma.so"

        vulkan_cmake=(
            -G Ninja
            -DCMAKE_TOOLCHAIN_FILE="${TOOLCHAIN_FILE}"
            -DCMAKE_POLICY_VERSION_MINIMUM=3.5
            -DANDROID_ABI="${abi}"
            -DANDROID_PLATFORM="android-${API}"
            -DANDROID_STL=c++_static
            -DCMAKE_BUILD_TYPE=Release
        )

        echo "==> Building shaderc for ${abi}"
        shaderc_build="${WORK}/shaderc-${abi}"
        # The tests and the command-line tools are host programs; cross-compiling them would need a
        # target that can run them, and nothing here calls them. NV extensions are left out for the
        # same reason Minecraft does not use them.
        cmake -S "${SHADERC_SRC}" -B "${shaderc_build}" "${vulkan_cmake[@]}" \
            -DSHADERC_SKIP_TESTS=ON \
            -DSHADERC_SKIP_EXAMPLES=ON \
            -DSHADERC_SKIP_COPYRIGHT_CHECK=ON \
            -DSHADERC_ENABLE_SHARED_CRT=ON \
            -DSPIRV_SKIP_EXECUTABLES=ON \
            -DSPIRV_SKIP_TESTS=ON \
            -DENABLE_GLSLANG_BINARIES=OFF \
            -DBUILD_SHARED_LIBS=OFF
        cmake --build "${shaderc_build}" --target shaderc_shared
        # Copied under upstream's own name rather than the one LWJGL looks for by default.
        # CMake writes the SONAME from the target name and appends its own -Wl,-soname after
        # anything the caller passes, so renaming the file would leave it disagreeing with the
        # SONAME the linker records it under. The launcher sets `org.lwjgl.shaderc.libname` instead.
        cp "${shaderc_build}/libshaderc/libshaderc_shared.so" "${out}/libshaderc_shared.so"

        echo "==> Building SPIRV-Cross for ${abi}"
        spvc_build="${WORK}/SPIRV-Cross-${abi}"
        # Only the C API is bound, and it is the only one with a stable ABI; the C++ interface is
        # explicitly not guaranteed between releases.
        cmake -S "${SPIRV_CROSS_SRC}" -B "${spvc_build}" "${vulkan_cmake[@]}" \
            -DSPIRV_CROSS_STATIC=OFF \
            -DSPIRV_CROSS_SHARED=ON \
            -DSPIRV_CROSS_CLI=OFF \
            -DSPIRV_CROSS_ENABLE_TESTS=OFF
        cmake --build "${spvc_build}" --target spirv-cross-c-shared
        cp "${spvc_build}/libspirv-cross-c-shared.so" "${out}/libspirv-cross-c-shared.so"
    fi

    # ----------------------------------------------------------------------------------------
    # Verification
    # ----------------------------------------------------------------------------------------
    # The JNI sets are packaged as assets, which the Android plugin copies verbatim rather than
    # stripping the way it does everything under `lib/`. Nothing here is reached except through the
    # dynamic symbol table, which stripping leaves alone, so the rest is a quarter of the set's size
    # spent on symbols no loader will ever read.
    while IFS= read -r lib; do
        "${STRIP}" "${lib}"
    done < <(find "${out}" -name '*.so')

    echo "==> Verifying ${abi}"
    for lib in liblwjgl liblwjgl_opengl liblwjgl_stb liblwjgl_tinyfd; do
        verify "${out}/${lib}.so" "${machine}" jni
    done
    if [[ "${LWJGL2}" == "yes" ]]; then
        # Read back the dynamic table specifically. This is the check the discarded objcopy
        # approach passed on `.symtab` while leaving `.dynsym` — the only table JNI reads —
        # entirely unrenamed.
        verify "${out}/lwjgl2/liblwjgl_opengl.so" "${machine}" jni
        stale="$("${READELF}" --dyn-syms "${out}/lwjgl2/liblwjgl_opengl.so" \
            | grep -c ' Java_org_lwjgl_opengl_' || true)"
        moved="$("${READELF}" --dyn-syms "${out}/lwjgl2/liblwjgl_opengl.so" \
            | grep -c " ${mangled}" || true)"
        [[ "${stale}" -eq 0 && "${moved}" -eq "${renamed}" ]] \
            || { echo "lwjgl2/liblwjgl_opengl.so exports ${stale} stale and ${moved} relocated" \
                 "JNI symbols, expected 0 and ${renamed}" >&2; exit 1; }
        printf '    %-24s %-30s %5s relocated under %s\n' \
            "lwjgl2/liblwjgl_opengl.so" "${machine}" "${moved}" "${LWJGL2_PACKAGE}.opengl"
    fi
    if [[ "${VULKAN}" == "yes" ]]; then
        verify "${out}/liblwjgl_vma.so" "${machine}" jni
        for lib in libshaderc_shared libspirv-cross-c-shared; do
            verify "${out}/${lib}.so" "${machine}" plain
        done
        # The bindings dispatch through libffi by symbol name, so a library that is present but
        # exports nothing under the expected name fails at the first shader compile rather than at
        # load. Read one entry point back off each to catch that here instead.
        #
        # Counted rather than matched with `grep -q`: this script runs under `pipefail`, and a
        # quiet grep closes the pipe on its first hit, which reaches readelf as SIGPIPE and fails
        # the whole pipeline for a symbol that is present. Counting reads the stream to the end.
        exports="$("${READELF}" --dyn-syms "${out}/libshaderc_shared.so" \
            | grep -c ' shaderc_compile_into_spv' || true)"
        [[ "${exports}" -gt 0 ]] \
            || { echo "libshaderc_shared.so exports no shaderc_compile_into_spv" >&2; exit 1; }
        exports="$("${READELF}" --dyn-syms "${out}/libspirv-cross-c-shared.so" \
            | grep -c ' spvc_context_create' || true)"
        [[ "${exports}" -gt 0 ]] \
            || { echo "libspirv-cross-c-shared.so exports no spvc_context_create" >&2; exit 1; }
        # Android's linker keys a loaded library on its SONAME rather than on the path it was
        # opened through, so a file whose name disagrees with its SONAME is a library the loader
        # knows by a name nothing asks for. Read back rather than assumed.
        for lib in libshaderc_shared libspirv-cross-c-shared; do
            soname="$("${READELF}" -d "${out}/${lib}.so" \
                | sed -n 's/.*Library soname: \[\(.*\)\].*/\1/p')"
            [[ "${soname}" == "${lib}.so" ]] \
                || { echo "${lib}.so has SONAME ${soname}, expected ${lib}.so" >&2; exit 1; }
        done

        # The whole entry-point contract, not the two spot checks above. LWJGL resolves each
        # module's functions in a class initialiser and throws on the first one missing, so a
        # library one symbol short loads cleanly and then fails deep inside the game — which is how
        # shaderc v2025.2 got as far as VulkanBackend.createDevice before anyone noticed it
        # predated shaderc_compile_options_set_max_id_bound.
        if command -v javap >/dev/null; then
            "$(dirname "$0")/check-lwjgl-exports.sh" --module shaderc --lwjgl "${LWJGL_VERSION}" \
                --ndk "${NDK}" --library "${out}/libshaderc_shared.so"
            "$(dirname "$0")/check-lwjgl-exports.sh" --module spvc --lwjgl "${LWJGL_VERSION}" \
                --ndk "${NDK}" --library "${out}/libspirv-cross-c-shared.so"
        else
            echo "    javap is not on PATH; skipped the shaderc and spvc entry-point check" >&2
        fi
    fi
    if [[ "${THIRD_PARTY}" == "yes" ]]; then
        for lib in libfreetype libopenal; do
            verify "${out}/${lib}.so" "${machine}" plain
        done
    fi
done

echo "==> LWJGL natives written to ${OUTPUT}"
