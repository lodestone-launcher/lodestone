#!/usr/bin/env bash
# Checks that a native library exports every symbol LWJGL's bindings will look for.
#
# LWJGL resolves a module's entry points in a static initialiser, through
# `APIUtil.apiGetFunctionAddress`, which throws when a symbol is absent. One missing export
# therefore does not degrade a feature — it takes the launch down at class initialisation, on a
# thread and at a moment that has nothing to do with whatever the game was actually asking for.
# Two of those have already happened here:
#
#   * a GLFW shim missing four Vulkan entry points, which would have failed inside GLFWVulkan
#     before the Vulkan backend was ever tried;
#   * shaderc v2025.2, which predates `shaderc_compile_options_set_max_id_bound` and so loaded
#     perfectly and then threw a missing-function NullPointerException from inside Shaderc's
#     initialiser, four frames below `VulkanBackend.createDevice`.
#
# Both are trivial to see here and miserable to diagnose on a device, so the whole contract is
# read out of the LWJGL release the launcher actually ships and diffed against the built library.
# Reading it from the jar rather than keeping a checked-in copy means a release that adds an entry
# point is caught the moment the version is bumped. `apiGetFunctionAddressOptional` is deliberately
# excluded: those are the ones LWJGL is willing to find missing.
#
# Usage: check-lwjgl-exports.sh --module glfw|shaderc|spvc --library <path> [--lwjgl 3.4.1]
#                               [--ndk <path>]
set -euo pipefail

MODULE=""
LIBRARY=""
LWJGL_VERSION="3.4.1"
NDK="${ANDROID_NDK_HOME:-${NDK_HOME:-}}"
WORK="${TMPDIR:-/tmp}/lodestone-lwjgl-exports"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --module) MODULE="$2"; shift 2 ;;
        --library) LIBRARY="$2"; shift 2 ;;
        --lwjgl) LWJGL_VERSION="$2"; shift 2 ;;
        --ndk) NDK="$2"; shift 2 ;;
        --work) WORK="$2"; shift 2 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

# Which jar to read, which classes inside it hold the function table, and the prefix every symbol
# of that module shares. GLFW has two tables: the core one and the Vulkan one, which LWJGL splits
# because a desktop GLFW may be built without Vulkan support — ours never is.
case "${MODULE}" in
    glfw)
        ARTIFACT="lwjgl-glfw"
        CLASSES=('org/lwjgl/glfw/GLFW$Functions' 'org/lwjgl/glfw/GLFWVulkan$Functions')
        PREFIX="glfw"
        ;;
    shaderc)
        ARTIFACT="lwjgl-shaderc"
        CLASSES=('org/lwjgl/util/shaderc/Shaderc$Functions')
        PREFIX="shaderc_"
        ;;
    spvc)
        ARTIFACT="lwjgl-spvc"
        CLASSES=('org/lwjgl/util/spvc/Spvc$Functions')
        PREFIX="spvc_"
        ;;
    *)
        echo "Pass --module glfw, shaderc or spvc" >&2; exit 2 ;;
esac

[[ -n "${LIBRARY}" ]] || { echo "Pass --library <path>" >&2; exit 2; }
[[ -f "${LIBRARY}" ]] || { echo "No library at ${LIBRARY}" >&2; exit 2; }
[[ -n "${NDK}" ]] || { echo "Set ANDROID_NDK_HOME or pass --ndk" >&2; exit 2; }

READELF="${NDK}/toolchains/llvm/prebuilt/$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64/bin/llvm-readelf"
[[ -x "${READELF}" ]] || { echo "No llvm-readelf at ${READELF}" >&2; exit 2; }
command -v javap >/dev/null || { echo "javap is needed to read the LWJGL bindings" >&2; exit 2; }

mkdir -p "${WORK}"
jar="${WORK}/${ARTIFACT}-${LWJGL_VERSION}.jar"
if [[ ! -f "${jar}" ]]; then
    echo "==> Fetching ${ARTIFACT} ${LWJGL_VERSION}"
    curl --fail --location --silent --show-error \
        "https://repo1.maven.org/maven2/org/lwjgl/${ARTIFACT}/${LWJGL_VERSION}/${ARTIFACT}-${LWJGL_VERSION}.jar" \
        --output "${jar}"
fi

classes="${WORK}/${MODULE}-${LWJGL_VERSION}"
rm -rf "${classes}"
mkdir -p "${classes}"
for class in "${CLASSES[@]}"; do
    unzip -q -o "${jar}" -d "${classes}" "${class}.class"
done

# Each entry point appears as the `ldc` of its name followed by the call that resolves it, so
# pairing the lines up puts the name and the resolution kind together.
required="${WORK}/${MODULE}-required.txt"
: > "${required}"
for class in "${CLASSES[@]}"; do
    file="${classes}/${class}.class"
    [[ -f "${file}" ]] || continue
    javap -p -c "${file}" \
        | grep -oE "String ${PREFIX}[A-Za-z0-9_]+|apiGetFunctionAddress(Optional)?" \
        | paste - - \
        | grep -v 'Optional' \
        | sed -e 's/^String //' -e 's/[[:space:]].*$//' >> "${required}"
done
sort -u "${required}" -o "${required}"

count="$(wc -l < "${required}" | tr -d ' ')"
[[ "${count}" -gt 0 ]] \
    || { echo "Read no symbols out of ${ARTIFACT}; the check is not doing anything" >&2; exit 1; }

# `grep -c` rather than `grep -q`: this runs under `pipefail`, and a quiet grep closes the pipe on
# its first hit, which reaches readelf as SIGPIPE and fails a pipeline that actually succeeded.
exported="${WORK}/${MODULE}-exported.txt"
"${READELF}" --dyn-syms "${LIBRARY}" | grep -oE "\\b${PREFIX}[A-Za-z0-9_]+" | sort -u > "${exported}"

missing="$(comm -23 "${required}" "${exported}")"
if [[ -n "${missing}" ]]; then
    echo "$(basename "${LIBRARY}") is missing $(echo "${missing}" | wc -l | tr -d ' ') of the" \
         "${count} ${MODULE} entry points LWJGL ${LWJGL_VERSION} resolves eagerly:" >&2
    echo "${missing}" | sed 's/^/    /' >&2
    exit 1
fi

echo "$(basename "${LIBRARY}") exports all ${count} ${MODULE} entry points LWJGL ${LWJGL_VERSION} requires"
