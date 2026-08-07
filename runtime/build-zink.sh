#!/usr/bin/env bash
# Cross-compiles Mesa's Zink, the desktop-OpenGL-over-Vulkan Gallium driver.
#
# gl4es tops out at OpenGL 2.1 with parts of 3.x, so it only reaches Minecraft 1.16.5 — which wants
# Java 8, the hardest runtime to build. Zink translates to Vulkan instead of GLES and reports GL 4.6,
# so it covers the 3.2 core profile 1.17+ needs, and those versions run on Java 17/21. That pairing
# is the shortest path to a first launch, which is why this exists alongside build-gl4es.sh.
#
# Turnip (`--vulkan-drivers freedreno`) is Mesa's own Adreno Vulkan driver. It is built because most
# Android devices are Adreno and vendor Vulkan blobs are frequently too broken for Zink, but it is a
# parameter: pass an empty list to fall back to the device's own `libvulkan.so`.
#
# Usage: build-zink.sh [--abi arm64-v8a] [--output <dir>] [--ndk <path>] [--api 30]
#                      [--gallium-drivers zink] [--vulkan-drivers freedreno] [--tag mesa-26.1.6]
set -euo pipefail

ABIS=()
OUTPUT="$(pwd)/out/zink"
NDK="${ANDROID_NDK_HOME:-${NDK_HOME:-}}"
API=30
GALLIUM_DRIVERS="zink"
VULKAN_DRIVERS="freedreno"
REPOSITORY="https://gitlab.freedesktop.org/mesa/mesa.git"
TAG="mesa-26.1.6"
WORK="${TMPDIR:-/tmp}/lodestone-zink"
PATCHES="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/patches/mesa"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --abi) ABIS+=("$2"); shift 2 ;;
        --output) OUTPUT="$2"; shift 2 ;;
        --ndk) NDK="$2"; shift 2 ;;
        --api) API="$2"; shift 2 ;;
        --work) WORK="$2"; shift 2 ;;
        --gallium-drivers) GALLIUM_DRIVERS="$2"; shift 2 ;;
        --vulkan-drivers) VULKAN_DRIVERS="$2"; shift 2 ;;
        --tag) TAG="$2"; shift 2 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

# Only arm64 by default, unlike gl4es: Turnip is Adreno-only, and no x86_64 Android device or
# emulator ships a Vulkan driver Zink can usefully sit on top of.
if [[ ${#ABIS[@]} -eq 0 ]]; then
    ABIS=(arm64-v8a)
fi
[[ -n "${NDK}" ]] || { echo "Set ANDROID_NDK_HOME or pass --ndk" >&2; exit 2; }
[[ -d "${NDK}" ]] || { echo "No NDK at ${NDK}" >&2; exit 2; }

case "$(uname -s)" in
    Darwin) HOST_TAG="darwin-x86_64" ;;
    Linux) HOST_TAG="linux-x86_64" ;;
    *) echo "Unsupported build host: $(uname -s)" >&2; exit 2 ;;
esac
TOOLCHAIN="${NDK}/toolchains/llvm/prebuilt/${HOST_TAG}"
[[ -d "${TOOLCHAIN}/bin" ]] || { echo "No NDK toolchain at ${TOOLCHAIN}" >&2; exit 2; }

# Mesa's code generators need bison 3. macOS still ships 2.3, which rejects the `%define api.prefix
# {...}` syntax in glcpp-parse.y, so prefer Homebrew's keg-only build when it is installed.
if [[ -d /opt/homebrew/opt/bison/bin ]]; then
    PATH="/opt/homebrew/opt/bison/bin:${PATH}"
fi
if ! bison --version | head -1 | grep -qE ' [3-9]\.'; then
    echo "bison 3 or newer required (brew install bison / apt install bison)" >&2
    exit 2
fi
if [[ -n "${VULKAN_DRIVERS}" ]] && ! command -v glslangValidator >/dev/null; then
    echo "glslangValidator required to build Vulkan drivers (brew install glslang)" >&2
    exit 2
fi

mkdir -p "${WORK}"

# Mesa wants Python 3.10+ with mako and pyyaml. macOS ships 3.9 and Homebrew's interpreter is
# externally managed, so pip cannot add mako to either — a venv is the only way to get all three
# without touching the host, and it doubles as the pinned meson and ninja.
VENV="${WORK}/venv"
if [[ ! -x "${VENV}/bin/meson" ]]; then
    echo "==> Preparing host build tools"
    PYTHON=""
    for candidate in python3.14 python3.13 python3.12 python3.11 python3.10 python3; do
        if command -v "${candidate}" >/dev/null && "${candidate}" -c 'import sys; sys.exit(sys.version_info < (3, 10))'; then
            PYTHON="${candidate}"
            break
        fi
    done
    [[ -n "${PYTHON}" ]] || { echo "Python 3.10 or newer required" >&2; exit 2; }
    "${PYTHON}" -m venv "${VENV}"
    "${VENV}/bin/pip" install --quiet --upgrade pip meson ninja mako pyyaml packaging
fi
PATH="${VENV}/bin:${PATH}"

SOURCE="${WORK}/mesa"
if [[ ! -d "${SOURCE}" ]]; then
    echo "==> Cloning Mesa ${TAG}"
    git clone --depth 1 --branch "${TAG}" "${REPOSITORY}" "${SOURCE}"
    for patch in "${PATCHES}"/*.patch; do
        [[ -f "${patch}" ]] || continue
        echo "    applying $(basename "${patch}")"
        git -C "${SOURCE}" apply "${patch}"
    done
fi

mkdir -p "${OUTPUT}"

for abi in "${ABIS[@]}"; do
    case "${abi}" in
        arm64-v8a) triple="aarch64-linux-android"; cpu_family="aarch64"; cpu="aarch64" ;;
        armeabi-v7a) triple="armv7a-linux-androideabi"; cpu_family="arm"; cpu="armv7a" ;;
        x86_64) triple="x86_64-linux-android"; cpu_family="x86_64"; cpu="x86_64" ;;
        x86) triple="i686-linux-android"; cpu_family="x86"; cpu="i686" ;;
        *) echo "Unknown ABI: ${abi}" >&2; exit 2 ;;
    esac

    echo "==> Building Mesa for ${abi}"
    cross="${WORK}/cross-${abi}.txt"
    build="${WORK}/build-${abi}"

    # An empty pkg-config search path is deliberate. Meson would otherwise answer host queries from
    # the build machine's own .pc files and link the Android build against Homebrew's x86/arm64
    # macOS libraries.
    mkdir -p "${WORK}/no-pkg-config"
    cat > "${cross}" <<EOF
[binaries]
c = '${TOOLCHAIN}/bin/${triple}${API}-clang'
cpp = '${TOOLCHAIN}/bin/${triple}${API}-clang++'
ar = '${TOOLCHAIN}/bin/llvm-ar'
strip = '${TOOLCHAIN}/bin/llvm-strip'

[host_machine]
system = 'android'
cpu_family = '${cpu_family}'
cpu = '${cpu}'
endian = 'little'

[properties]
pkg_config_libdir = '${WORK}/no-pkg-config'
needs_exe_wrapper = true
EOF

    # A Mesa build from scratch is a quarter of an hour, so reuse the build directory when it is
    # already there and let meson work out what the changed options invalidate.
    setup=(meson setup "${build}" "${SOURCE}")
    [[ -d "${build}" ]] && setup+=(--reconfigure)

    # Every option below was checked against Mesa 26's meson.options, not assumed:
    #  - `android-stub` builds the Android system libraries Mesa expects (cutils, hardware, log,
    #    sync, nativewindow) as local no-op stubs. Without it Mesa needs Android's own build system,
    #    because the NDK ships neither libcutils nor libhardware nor their .pc files.
    #  - `android-strict=false` lets drivers advertise capabilities Mesa hides for CTS compliance.
    #    We are not shipping a certified driver; we want the extensions Zink can expose.
    #  - `freedreno-kmds` keeps both kernel interfaces: Android talks to Adreno through kgsl, but
    #    devices that expose the upstream msm DRM node are worth keeping reachable.
    #  - `glx` and `gbm` need X11 and KMS respectively, neither of which exists here.
    #  - `llvm` is only used by the software rasterisers, and Zink has no use for it.
    #  - `xmlconfig` off drops driconf, which in turn drops the expat dependency entirely.
    #  - libdrm has to come from the subproject wrap; Android has no system copy.
    "${setup[@]}" \
        --cross-file "${cross}" \
        --buildtype release \
        --default-library shared \
        --force-fallback-for=libdrm \
        -Dallow-fallback-for=libdrm,perfetto \
        -Dplatforms=android \
        -Dandroid-stub=true \
        -Dandroid-strict=false \
        -Dplatform-sdk-version="${API}" \
        -Degl-native-platform=android \
        -Dgallium-drivers="${GALLIUM_DRIVERS}" \
        -Dvulkan-drivers="${VULKAN_DRIVERS}" \
        -Dfreedreno-kmds=kgsl,msm \
        -Dglx=disabled \
        -Degl=enabled \
        -Dgbm=disabled \
        -Dllvm=disabled \
        -Dopengl=true \
        -Dgles1=disabled \
        -Dgles2=enabled \
        -Dxmlconfig=disabled \
        -Dexpat=disabled \
        -Dzstd=disabled \
        -Dvalgrind=disabled \
        -Dlibunwind=disabled \
        -Dlmsensors=disabled \
        -Ddisplay-info=disabled \
        -Dbuild-tests=false \
        -Dvideo-codecs= \
        -Dtools=

    ninja -C "${build}"

    destination="${OUTPUT}/${abi}"
    mkdir -p "${destination}"
    rm -f "${destination}"/*.so

    # Mesa already names everything `lib*.so` on Android — meson drops the soversion for this
    # target, so there is no `libGL.so.1` to rename the way gl4es needed.
    while IFS= read -r library; do
        cp "${library}" "${destination}/"
    done < <(find "${build}/src" -name '*.so' -not -path '*.so.p/*' -not -path '*android_stub*')

    # Only these two stubs get shipped. liblog, libsync and libnativewindow are on Android's public
    # library list, so the real implementations resolve at runtime and shipping no-op stubs beside
    # them would replace working platform code with nothing. libcutils and libhardware are not
    # public, so nothing would resolve them at all.
    cp "${build}/src/android_stub/libcutils.so" "${destination}/"
    cp "${build}/src/android_stub/libhardware.so" "${destination}/"

    cp "${build}"/subprojects/libdrm-*/libdrm.so "${destination}/"
    cp "${TOOLCHAIN}/sysroot/usr/lib/${triple}/libc++_shared.so" "${destination}/"

    [[ -f "${destination}/libGL.so" ]] || { echo "No libGL.so produced for ${abi}" >&2; exit 1; }
    for library in "${destination}"/*.so; do
        printf '    %-28s %s bytes\n' "$(basename "${library}")" "$(wc -c < "${library}" | tr -d ' ')"
    done
done

echo "==> Zink written to ${OUTPUT}"
