#!/usr/bin/env bash
# Cross-compiles OpenJDK for Android and packages it as a Lodestone runtime tarball.
#
# Usage: build-jdk.sh --feature 21 --abi arm64-v8a [--tag jdk-21.0.9-ga] [--output /work/out]
#
# The Java runtime Minecraft asks for is named by a `javaVersion.component` in its manifest
# (`jre-legacy` = 8, `java-runtime-gamma` = 17, `java-runtime-delta` = 21,
# `java-runtime-epsilon` = 25). This script produces one runtime per (feature, ABI) pair, and the
# launcher picks between them from that field.
set -euo pipefail

FEATURE=""
ABI=""
TAG=""
OUTPUT="/work/out"
SOURCE_ROOT="/work/src"
JOBS="$(nproc)"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --feature) FEATURE="$2"; shift 2 ;;
        --abi) ABI="$2"; shift 2 ;;
        --tag) TAG="$2"; shift 2 ;;
        --output) OUTPUT="$2"; shift 2 ;;
        --jobs) JOBS="$2"; shift 2 ;;
        --clean) CLEAN_BUILD=1; shift ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

[[ -n "${FEATURE}" ]] || { echo "--feature is required" >&2; exit 2; }
[[ -n "${ABI}" ]] || { echo "--abi is required" >&2; exit 2; }

NDK_HOME="${NDK_HOME:-/opt/android-ndk}"
BOOT_JDK_ROOT="${BOOT_JDK_ROOT:-/opt/boot-jdks}"
PATCH_ROOT="${PATCH_ROOT:-/work/patches}"

# The API level the runtime targets. It must not exceed the app's minSdk, or the JVM will fail to
# load on devices the launcher claims to support.
#
# 30 rather than 26 because HotSpot calls posix_spawn (bionic API 28), getloadavg (29) and dlinfo
# (30). Those are real functions in bionic, just gated behind newer API levels, so raising the
# target is a one-line change where patching around them would mean three separate fallbacks in
# os_linux.cpp and os_posix.cpp.
ANDROID_API="${ANDROID_API:-30}"

# ---------------------------------------------------------------------------------------------
# Toolchain selection
# ---------------------------------------------------------------------------------------------
case "${ABI}" in
    arm64-v8a)
        TRIPLE="aarch64-linux-android"
        # OpenJDK's configure only understands the GNU triplets it ships autoconf fragments for.
        # We hand it the glibc triplet and override every tool below, so it configures a normal
        # 64-bit ARM Linux build while actually compiling against bionic.
        OPENJDK_TARGET="aarch64-unknown-linux-gnu"
        JVM_VARIANT="server"
        ;;
    x86_64)
        TRIPLE="x86_64-linux-android"
        OPENJDK_TARGET="x86_64-unknown-linux-gnu"
        JVM_VARIANT="server"
        ;;
    *)
        echo "Unsupported ABI: ${ABI}" >&2
        exit 2
        ;;
esac

TOOLCHAIN="${NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64"
SYSROOT="${TOOLCHAIN}/sysroot"
export CC="${TOOLCHAIN}/bin/${TRIPLE}${ANDROID_API}-clang"
export CXX="${TOOLCHAIN}/bin/${TRIPLE}${ANDROID_API}-clang++"
export AR="${TOOLCHAIN}/bin/llvm-ar"
export NM="${TOOLCHAIN}/bin/llvm-nm"
export RANLIB="${TOOLCHAIN}/bin/llvm-ranlib"
export STRIP="${TOOLCHAIN}/bin/llvm-strip"
export OBJCOPY="${TOOLCHAIN}/bin/llvm-objcopy"
export OBJDUMP="${TOOLCHAIN}/bin/llvm-objdump"
export READELF="${TOOLCHAIN}/bin/llvm-readelf"

# Tools that run on the build host during the cross build must stay native.
# Must be clang as well: configure rejects a gcc build compiler once the target toolchain is clang.
export BUILD_CC=/usr/bin/clang
export BUILD_CXX=/usr/bin/clang++

# ---------------------------------------------------------------------------------------------
# Source
# ---------------------------------------------------------------------------------------------
case "${FEATURE}" in
    8)  REPO="https://github.com/openjdk/jdk8u.git" ;;
    17) REPO="https://github.com/openjdk/jdk17u.git" ;;
    21) REPO="https://github.com/openjdk/jdk21u.git" ;;
    25) REPO="https://github.com/openjdk/jdk25u.git" ;;
    *)  echo "No known repository for feature release ${FEATURE}" >&2; exit 2 ;;
esac

SRC="${SOURCE_ROOT}/jdk${FEATURE}u"
if [[ ! -d "${SRC}" ]]; then
    echo "==> Cloning ${REPO}"
    mkdir -p "${SOURCE_ROOT}"
    if [[ -n "${TAG}" ]]; then
        git clone --depth 1 --branch "${TAG}" "${REPO}" "${SRC}"
    else
        git clone --depth 1 "${REPO}" "${SRC}"
    fi
fi

# The source tree is a mounted volume that survives between runs, so it has to be reset before
# patching or an edited patch would be silently skipped as "previously applied" and the build would
# quietly use the old version.
if [[ -d "${SRC}/.git" ]]; then
    echo "==> Resetting ${SRC} to a pristine checkout"
    git -C "${SRC}" checkout -- .
    git -C "${SRC}" clean -fd -e build >/dev/null
fi

PATCH_DIR="${PATCH_ROOT}/jdk${FEATURE}"
if [[ -d "${PATCH_DIR}" ]]; then
    echo "==> Applying patches from ${PATCH_DIR}"
    for patch in "${PATCH_DIR}"/*.patch; do
        [[ -e "${patch}" ]] || continue
        echo "    $(basename "${patch}")"
        # --forward keeps a re-run on an already-patched tree from failing the build.
        patch -p1 --forward --directory="${SRC}" < "${patch}" || {
            status=$?
            # Exit status 1 from `patch --forward` means "already applied", which is fine.
            [[ ${status} -eq 1 ]] || exit ${status}
        }
    done
else
    echo "==> No patch directory at ${PATCH_DIR}; building unpatched"
fi

# An update release bootstraps from its own feature version, which is simpler to obtain than the
# N-1 release upstream nominally requires and is what the jdk*u maintainers themselves test with.
BOOT_JDK="${BOOT_JDK_ROOT}/jdk-${FEATURE}"
[[ -d "${BOOT_JDK}" ]] || { echo "Missing boot JDK at ${BOOT_JDK}" >&2; exit 1; }

# ---------------------------------------------------------------------------------------------
# Configure
# ---------------------------------------------------------------------------------------------
BUILD_DIR="${SRC}/build/android-${ABI}"
# Deliberately kept between runs. OpenJDK reconfigures happily into an existing build directory and
# make skips what is already up to date, which turns a one-line patch fix from a full rebuild of
# ~1200 HotSpot objects into a couple of minutes. Pass --clean to start over.
if [[ "${CLEAN_BUILD:-0}" == "1" ]]; then
    rm -rf "${BUILD_DIR}"
fi

# bionic folds pthread, rt and dl into libc, so the separate -l flags OpenJDK emits do not resolve.
# `-landroid -llog` give the VM access to the platform logger for its own diagnostics.
EXTRA_CFLAGS=(
    "-D__ANDROID_API__=${ANDROID_API}"
    "-DNO_ZLIB_UNCOMPRESS2"
    "-fPIC"
    "-Wno-error"
    "-Wno-unused-command-line-argument"
)
EXTRA_LDFLAGS=(
    "-landroid"
    "-llog"
    # No -rpath entries here on purpose. OpenJDK already emits `-Wl,-rpath,$ORIGIN` for every
    # library it links, and appends `-Wl,--disable-new-dtags` after this list, so an
    # `--enable-new-dtags` added here would simply be overridden. The 0006 patch flips that flag
    # at its source instead, which also spares us re-deriving OpenJDK's `\$$ORIGIN` escaping.
    "-Wl,--allow-shlib-undefined"
    # HotSpot's version script names symbols that do not survive optimisation — vtables for classes
    # local to the WhiteBox test functions. GNU ld warns about those; lld now defaults to
    # --no-undefined-version and treats them as errors. This restores the permissive behaviour the
    # script was written against.
    "-Wl,--undefined-version"
)

CONFIGURE_ARGS=(
    "--openjdk-target=${OPENJDK_TARGET}"
    "--with-boot-jdk=${BOOT_JDK}"
    "--with-sysroot=${SYSROOT}"
    "--with-toolchain-type=clang"
    "--with-jvm-variants=${JVM_VARIANT}"
    "--with-debug-level=release"
    "--with-native-debug-symbols=none"
    "--disable-precompiled-headers"
    "--with-extra-cflags=${EXTRA_CFLAGS[*]}"
    "--with-extra-cxxflags=${EXTRA_CFLAGS[*]}"
    "--with-extra-ldflags=${EXTRA_LDFLAGS[*]}"
    # freetype is the one java.desktop dependency OpenJDK links rather than dlopens, so staging
    # headers cannot satisfy it and there is no aarch64-android library to link against. OpenJDK
    # carries its own freetype source for exactly this case; building it for the target sidesteps
    # the host library entirely.
    # `--enable-headless-only` does not stop jdk21u from requiring X11, and the check is autoconf's
    # AC_PATH_X, which does its own search and ignores --with-sysroot entirely. These are that
    # macro's documented cross-compile escape hatch, pointing it at the staged headers and stubs.
    "--x-includes=${SYSROOT}/usr/include"
    "--x-libraries=${SYSROOT}/usr/lib/aarch64-linux-android/${ANDROID_API}"
    "--with-freetype=bundled"
    "--with-giflib=bundled"
    "--with-zlib=bundled"
    # configure prints "Ignoring value of CC from the environment. Use command line variables
    # instead" and then picks up the host gcc, so every tool has to be passed as an assignment.
    "CC=${CC}"
    "CXX=${CXX}"
    "AR=${AR}"
    "NM=${NM}"
    "RANLIB=${RANLIB}"
    "STRIP=${STRIP}"
    "OBJCOPY=${OBJCOPY}"
    "OBJDUMP=${OBJDUMP}"
    "READELF=${READELF}"
    "BUILD_CC=${BUILD_CC}"
    "BUILD_CXX=${BUILD_CXX}"
)

if [[ "${FEATURE}" == "8" ]]; then
    # 8 predates every one of these. Headless is `--disable-headful` (which the 0010 patch teaches
    # the build to honour); there is no `--disable-warnings-as-errors`, so the 0008 patch turns off
    # the one place that promotes them; there is no `--with-jvm-features`, and 8's HotSpot builds
    # no DTrace on linux-aarch64 to begin with; and libjpeg and libpng are always vendored, with no
    # switch to say so. Passing them anyway is not harmless: configure answers "unrecognized
    # options" and stops before writing spec.gmk, while still exiting 0.
    CONFIGURE_ARGS+=("--disable-headful")
else
    CONFIGURE_ARGS+=("--enable-headless-only")
    CONFIGURE_ARGS+=("--disable-warnings-as-errors")
    # Android has no DTrace and no CDS archive to dump at build time on a foreign architecture.
    CONFIGURE_ARGS+=("--with-jvm-features=-dtrace")
    # Vendored for the same reason as freetype: the system copies in the image are x86_64.
    CONFIGURE_ARGS+=("--with-libjpeg=bundled")
    CONFIGURE_ARGS+=("--with-libpng=bundled")
    # `--with-build-jdk` lets the cross build run jlink and friends natively instead of trying to
    # execute freshly built Android binaries on the build host.
    CONFIGURE_ARGS+=("--with-build-jdk=${BOOT_JDK}")
    CONFIGURE_ARGS+=("--enable-option-checking=fatal")
fi

echo "==> Configuring OpenJDK ${FEATURE} for ${ABI}"
mkdir -p "${BUILD_DIR}"
(
    cd "${SRC}"
    bash configure --with-conf-name="android-${ABI}" "${CONFIGURE_ARGS[@]}" 2>&1 | tee "${BUILD_DIR}/lodestone-configure.log"
)
# configure exits 0 after refusing an option it does not know, so its status says nothing. The
# published Java 8 runtime was built from a spec.gmk left behind by an earlier, different configure
# for exactly this reason, and the committed arguments could no longer reproduce it.
if grep -q '^configure: error:' "${BUILD_DIR}/lodestone-configure.log"; then
    echo "configure reported an error above and did not complete" >&2
    exit 1
fi
[[ -f "${BUILD_DIR}/spec.gmk" ]] || { echo "configure produced no ${BUILD_DIR}/spec.gmk" >&2; exit 1; }

# ---------------------------------------------------------------------------------------------
# Build and package
# ---------------------------------------------------------------------------------------------
echo "==> Building with ${JOBS} jobs"
make -C "${SRC}" CONF_NAME="android-${ABI}" JOBS="${JOBS}" images

IMAGE_DIR="${BUILD_DIR}/images/jdk"
[[ -d "${IMAGE_DIR}" ]] || IMAGE_DIR="${BUILD_DIR}/images/j2sdk-image"
[[ -d "${IMAGE_DIR}" ]] || { echo "No image produced under ${BUILD_DIR}/images" >&2; exit 1; }

# Deliberately before packaging, so a runtime that cannot load produces no tarball to publish. A
# shared library is allowed to leave symbols undefined and the linker says nothing about the ones
# no library will ever define, which is how a libjvm.so missing a template instantiation shipped
# and failed at `dlopen` on every device.
"$(dirname "$0")/audit-image.sh" --image "${IMAGE_DIR}" --abi "${ABI}" --api "${ANDROID_API}"

mkdir -p "${OUTPUT}"
ARCHIVE="${OUTPUT}/lodestone-jre${FEATURE}-${ABI}.tar.gz"

echo "==> Packaging ${ARCHIVE}"
# Android's installer refuses to extract files with an executable bit it does not expect, and the
# launcher restores permissions itself, so the archive stores a plain tree.
tar -czf "${ARCHIVE}" -C "$(dirname "${IMAGE_DIR}")" "$(basename "${IMAGE_DIR}")"
( cd "${OUTPUT}" && sha256sum "$(basename "${ARCHIVE}")" > "$(basename "${ARCHIVE}").sha256" )

echo "==> Done: ${ARCHIVE}"
ls -la "${OUTPUT}"
