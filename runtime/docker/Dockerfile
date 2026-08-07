# Build environment for cross-compiling OpenJDK to Android/bionic.
#
# The image is deliberately self-contained: the same image runs in GitHub Actions and on a
# developer's machine, so a build that succeeds locally cannot fail in CI because of a differently
# patched host toolchain.
FROM ubuntu:24.04

ARG NDK_VERSION=r28c
ARG DEBIAN_FRONTEND=noninteractive

# OpenJDK's configure needs a full autoconf toolchain plus a native compiler for the build-host
# tools it generates and runs during the cross build. That native compiler must also be clang:
# configure refuses to mix toolchain types, and --with-toolchain-type=clang applies to both.
RUN apt-get update && apt-get install -y --no-install-recommends \
        autoconf \
        build-essential \
        clang \
        llvm \
        ca-certificates \
        cpio \
        curl \
        file \
        git \
        make \
        patch \
        python3 \
        rsync \
        unzip \
        wget \
        zip \
        zlib1g-dev \
    && rm -rf /var/lib/apt/lists/*

ENV NDK_HOME=/opt/android-ndk
RUN wget -q "https://dl.google.com/android/repository/android-ndk-${NDK_VERSION}-linux.zip" -O /tmp/ndk.zip \
    && unzip -q /tmp/ndk.zip -d /opt \
    && mv "/opt/android-ndk-${NDK_VERSION}" "${NDK_HOME}" \
    && rm /tmp/ndk.zip

# OpenJDK bootstraps from the previous feature release, so every JDK we build needs its own boot
# JDK on the build host. These are ordinary x86_64 Linux builds — only the *output* is Android.
ENV BOOT_JDK_ROOT=/opt/boot-jdks
COPY fetch-boot-jdks.sh /usr/local/bin/fetch-boot-jdks.sh
RUN chmod +x /usr/local/bin/fetch-boot-jdks.sh && /usr/local/bin/fetch-boot-jdks.sh

# Deliberately a separate, later layer: editing the first apt block would invalidate the NDK and
# boot-JDK layers above it and force several gigabytes to download again.
#
# OpenJDK's java.desktop module wants ALSA headers even under --enable-headless-only. Android has
# no ALSA, but only the headers are needed to compile: libjsound dlopens the library at runtime and
# fails harmlessly when it is absent. Minecraft drives audio through OpenAL, not javax.sound, so
# nothing the game does depends on it working.
RUN apt-get update && apt-get install -y --no-install-recommends \
        libasound2-dev \
        libcups2-dev \
        libfontconfig1-dev \
        libx11-dev libxext-dev libxrender-dev libxrandr-dev libxtst-dev libxt-dev \
    && rm -rf /var/lib/apt/lists/*

COPY build-jdk.sh /usr/local/bin/build-jdk.sh
RUN chmod +x /usr/local/bin/build-jdk.sh

WORKDIR /work
ENTRYPOINT ["/usr/local/bin/build-jdk.sh"]
