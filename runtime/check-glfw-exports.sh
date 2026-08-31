#!/usr/bin/env bash
# Checks that the GLFW shim exports every symbol LWJGL will look for.
#
# LWJGL's `GLFW.Functions` and `GLFWVulkan.Functions` resolve their entry points in a static
# initialiser, through `APIUtil.apiGetFunctionAddress`, which throws when a symbol is absent. One
# missing export therefore does not degrade a feature — it takes the whole launch down at class
# initialisation, before the game has drawn anything, with a message naming a function nobody asked
# for. That is a miserable thing to diagnose on a device and a trivial thing to check here.
#
# The list is read out of the LWJGL release the launcher actually ships rather than kept as a
# checked-in copy, so a release that adds an entry point is caught the moment the version is
# bumped. `apiGetFunctionAddressOptional` is deliberately excluded: those are the ones LWJGL is
# willing to find missing, and the shim is free not to have them.
#
# Usage: check-glfw-exports.sh --library <liblodestone_glfw.so> [--lwjgl 3.4.1] [--ndk <path>]
set -euo pipefail

LIBRARY=""
LWJGL_VERSION="3.4.1"
NDK="${ANDROID_NDK_HOME:-${NDK_HOME:-}}"
WORK="${TMPDIR:-/tmp}/lodestone-glfw-exports"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --library) LIBRARY="$2"; shift 2 ;;
        --lwjgl) LWJGL_VERSION="$2"; shift 2 ;;
        --ndk) NDK="$2"; shift 2 ;;
        --work) WORK="$2"; shift 2 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

[[ -n "${LIBRARY}" ]] || { echo "Pass --library <liblodestone_glfw.so>" >&2; exit 2; }
[[ -f "${LIBRARY}" ]] || { echo "No library at ${LIBRARY}" >&2; exit 2; }
[[ -n "${NDK}" ]] || { echo "Set ANDROID_NDK_HOME or pass --ndk" >&2; exit 2; }

READELF="${NDK}/toolchains/llvm/prebuilt/$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64/bin/llvm-readelf"
[[ -x "${READELF}" ]] || { echo "No llvm-readelf at ${READELF}" >&2; exit 2; }
command -v javap >/dev/null || { echo "javap is needed to read the LWJGL bindings" >&2; exit 2; }

mkdir -p "${WORK}"
jar="${WORK}/lwjgl-glfw-${LWJGL_VERSION}.jar"
if [[ ! -f "${jar}" ]]; then
    echo "==> Fetching LWJGL ${LWJGL_VERSION} GLFW bindings"
    curl --fail --location --silent --show-error \
        "https://repo1.maven.org/maven2/org/lwjgl/lwjgl-glfw/${LWJGL_VERSION}/lwjgl-glfw-${LWJGL_VERSION}.jar" \
        --output "${jar}"
fi

classes="${WORK}/classes-${LWJGL_VERSION}"
rm -rf "${classes}"
mkdir -p "${classes}"
unzip -q -o "${jar}" -d "${classes}" 'org/lwjgl/glfw/GLFW$Functions.class' \
    'org/lwjgl/glfw/GLFWVulkan$Functions.class'

# Each entry point appears as the `ldc` of its name followed by the call that resolves it, so the
# name and the resolution kind land on one line once paired up. Anything resolved through the
# Optional variant is dropped; the rest are mandatory.
required="${WORK}/required.txt"
: > "${required}"
for class in 'GLFW$Functions' 'GLFWVulkan$Functions'; do
    file="${classes}/org/lwjgl/glfw/${class}.class"
    [[ -f "${file}" ]] || continue
    javap -p -c "${file}" \
        | grep -oE 'String glfw[A-Za-z0-9]+|apiGetFunctionAddress(Optional)?' \
        | paste - - \
        | grep -v 'Optional' \
        | sed -e 's/^String //' -e 's/[[:space:]].*$//' >> "${required}"
done
sort -u "${required}" -o "${required}"

count="$(wc -l < "${required}" | tr -d ' ')"
[[ "${count}" -gt 0 ]] \
    || { echo "Read no symbols out of the LWJGL bindings; the check is not doing anything" >&2; exit 1; }

exported="${WORK}/exported.txt"
"${READELF}" --dyn-syms "${LIBRARY}" | grep -oE '\bglfw[A-Za-z0-9]+' | sort -u > "${exported}"

missing="$(comm -23 "${required}" "${exported}")"
if [[ -n "${missing}" ]]; then
    echo "$(basename "${LIBRARY}") is missing $(echo "${missing}" | wc -l | tr -d ' ') of the" \
         "${count} entry points LWJGL ${LWJGL_VERSION} resolves eagerly:" >&2
    echo "${missing}" | sed 's/^/    /' >&2
    exit 1
fi

echo "$(basename "${LIBRARY}") exports all ${count} entry points LWJGL ${LWJGL_VERSION} requires"
