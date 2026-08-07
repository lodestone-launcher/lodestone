# Patches for Android

`build-jdk.sh` applies every `*.patch` in `jdk<feature>/` to a pristine checkout with `patch -p1`
before configuring. `build-zink.sh` does the same with `mesa/` and `git apply`. Patches are numbered
so their order is stable (`0001-…`, `0002-…`).

## Mesa

- **`mesa/0001-export-desktop-gl-entry-points-on-android.patch`** — Mesa only links the desktop GL
  entry points into a `libGL.so` from its GLX provider, and GLX cannot be built on Android, so an
  Android build exposes desktop GL solely through `eglGetProcAddress`. LWJGL does not use that: it
  `dlopen`s whatever `org.lwjgl.opengl.libname` names and resolves each function with `dlsym`, so
  it needs a library that actually exports the symbols. The patch links Mesa's own
  `libglapi_bridge` — the same object GLX uses — into a `libGL.so` when the platform is Android.

# OpenJDK

Upstream OpenJDK has no Android target. It builds against glibc, and the build script papers over
part of the gap by handing `configure` the `*-unknown-linux-gnu` triplet while pointing every tool
at the NDK — so OpenJDK configures an ordinary 64-bit Linux build and then compiles it against
bionic. These patches cover the rest.

## Verified against jdk21u source and the NDK r28 sysroot

Everything below was checked against a real `openjdk/jdk21u` checkout and the bionic headers in
NDK 28.2, not assumed.

### Already portable — no patch needed

The gap is considerably smaller than it looks from the outside, because HotSpot has been made
portable for musl and for the platforms that lack these APIs:

- **`dlvsym`** — `os_linux.cpp` already ships its own fallback for platforms without it.
- **`mallinfo` / `mallinfo2`** — resolved dynamically at runtime through `dlsym`, so there is no
  compile-time dependency on glibc's struct layout.
- **`SIGRTMIN` / `SIGRTMAX`** — bionic defines both (`signal.h` maps `SIGRTMIN` to
  `__libc_current_sigrtmin()`). HotSpot's suspend/resume signal is `SIGUSR2` regardless, so the
  real-time range is only used for signal *naming*.
- **`pthread_getattr_np`** — present in bionic since API 21.
- **`getpwuid_r`** — present. It returns a synthetic entry on Android rather than a real passwd
  record, but the call succeeds, which is all the initialisation path requires. The launcher passes
  `-Duser.home` explicitly, so the synthetic value is never used.

### Patches written

- **`jdk21/0001-bionic-libc-version-detection.patch`** — bionic defines neither
  `_CS_GNU_LIBC_VERSION` nor `_CS_GNU_LIBPTHREAD_VERSION`, so `os::Linux::libpthread_init` trips its
  `#error "glibc too old (< 2.3.2)"` guard. Adds a `__BIONIC__` branch alongside the existing
  `MUSL_LIBC` one. Verified to apply cleanly with `git apply --check`.

### Blockers found by actually running the build

These came out of real `configure` runs in the Docker image, in the order they appeared.

1. **Toolchain discarded from the environment.** `configure` prints *"Ignoring value of CC from the
   environment. Use command line variables instead"* and then silently falls back to the host gcc,
   so the NDK toolchain was never used. Fixed by passing `CC=`, `CXX=`, `AR=` and the rest as
   configure assignments rather than exports.
2. **Build compiler must match the target toolchain.** With `--with-toolchain-type=clang`, a gcc
   *build* compiler is rejected outright. Fixed by installing clang and pointing `BUILD_CC` /
   `BUILD_CXX` at it.
3. **ALSA — still open.** `java.desktop` requires ALSA even under `--enable-headless-only`, and
   installing `libasound2-dev` on the build host does *not* satisfy it: this is a cross build, so
   `configure` looks inside the target sysroot given by `--with-sysroot`, where Android has no ALSA
   at all and never will.

   Three ways forward, in order of preference:

   - Point `--with-alsa-include` at the host headers. They are architecture-independent, so this
     gets past configure — but `libjsound` links `-lasound`, so expect the failure to move to the
     link step, where there is no aarch64-android library to satisfy it.
   - Stage the ALSA headers plus a stub `libasound.so` built for the target into the sysroot. Ugly,
     but it keeps the module building and `libjsound` merely fails to dlopen at runtime.
   - Drop the sound provider from `java.desktop` with a patch. The most honest option: Minecraft
     drives all audio through OpenAL, so nothing the game does touches `javax.sound`.

   The third is probably right, but it needs a patch written against the `java.desktop` makefiles
   rather than a configure flag.

### Still expected, to be confirmed by a real build

These have not been reproduced yet — the first CI run is what turns them into concrete errors with
line numbers, and some may prove to be non-issues like the ones above.

- **`-lpthread`, `-lrt`, `-ldl`** — bionic folds all three into libc, so these link flags do not
  resolve. `build-jdk.sh` compensates through its extra ldflags, but the makefiles name them in
  several places and may need editing too.
- **`jspawnhelper`** — `ProcessBuilder` execs a helper binary. It compiles, but Android's SELinux
  policy blocks executing files from app-writable storage on recent releases. Minecraft needs this
  only for its crash reporter, so making the failure non-fatal is preferable to making exec work.
- **Locale and charset** — bionic has no locale database, so `nl_langinfo` yields nothing useful.
  The launcher already pins `-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8`, but the initialisation
  path must tolerate the missing locale rather than assert.
- **Large pages** — must be forced off; Android does not expose `hugetlbfs`.
- **CDS archive** — cannot be dumped at build time for a foreign architecture.

### JDK 8 specifically

`jdk8u` predates the unified build system: HotSpot still builds from its own makefiles under
`hotspot/make`, which detect the compiler and platform separately from `configure`. It is by some
margin the hardest of the four, and it also has none of the musl portability work the modern
releases inherited. Build 17, 21 and 25 first.

## Which runtimes Minecraft actually asks for

| `javaVersion.component` | Feature release | Minecraft versions      |
|-------------------------|-----------------|-------------------------|
| `jre-legacy`            | 8               | earliest through 1.16.5 |
| `java-runtime-alpha`    | 16              | 1.17 and 1.17.1         |
| `java-runtime-beta`     | 17              | 1.18 through 1.20.4     |
| `java-runtime-gamma`    | 17              | 1.18 through 1.20.4     |
| `java-runtime-delta`    | 21              | 1.20.5 through 1.21.x   |
| `java-runtime-epsilon`  | 25              | 26.x                    |

Java 16 is not built separately: it is out of support and 17 runs 1.17 without issue, so the
launcher maps `java-runtime-alpha` onto the 17 runtime.

## Note on which Minecraft versions are reachable

The graphics side constrains this as much as the runtime side. gl4es covers OpenGL 2.1 with parts
of 3.x, which is enough up to **1.16.5** — and 1.16.5 wants Java 8, the hardest runtime to build.
From **1.17** onwards the game needs a 3.2 core profile, which means Zink over Vulkan instead of
gl4es, but those versions want Java 17 or 21, which are the easiest runtimes to build.

So neither end of the range is reachable by doing only one of the two jobs. The shortest path to a
first launch is **Java 17 + Zink + 1.18**, not the Java 8 + gl4es combination the presence of gl4es
might suggest.
