#!/usr/bin/env bash
# Downloads the host JDKs that OpenJDK bootstraps from.
#
# Building feature release N requires a working JDK N-1 (and, for some releases, N itself as a
# build JDK for the cross-compilation tooling). We fetch one per release we target rather than
# relying on whatever the distribution happens to package.
set -euo pipefail

BOOT_JDK_ROOT="${BOOT_JDK_ROOT:-/opt/boot-jdks}"
mkdir -p "${BOOT_JDK_ROOT}"

# Boot JDKs, newest first. Java 8 is bootstrapped by Java 7 in principle, but every current
# jdk8u tip builds cleanly with a JDK 8 boot JDK, which is far easier to obtain.
FEATURE_VERSIONS=(8 17 21 25)

for feature in "${FEATURE_VERSIONS[@]}"; do
    target="${BOOT_JDK_ROOT}/jdk-${feature}"
    if [[ -d "${target}" ]]; then
        continue
    fi
    echo "==> Fetching boot JDK ${feature}"
    url="https://api.adoptium.net/v3/binary/latest/${feature}/ga/linux/x64/jdk/hotspot/normal/eclipse"
    mkdir -p "${target}"
    # Adoptium archives carry a single top-level directory; strip it so the layout is predictable.
    curl -fsSL "${url}" | tar -xz -C "${target}" --strip-components=1
    "${target}/bin/java" -version
done
