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
- **`mesa/0002-allow-the-desktop-gl-api-to-be-bound-on-android.patch`** — `_eglIsApiValid` refuses
  `EGL_OPENGL_API` on Android, so `eglBindAPI(EGL_OPENGL_API)` answers `EGL_BAD_PARAMETER` and a
  context can only ever be an ES one. Zink's entire purpose here is a desktop GL context, so the
  Android exclusion is dropped. Verified on the artifact rather than the build log: `eglBindAPI` in
  the resulting `libEGL_zink.so` compiles to `(api & ~2) == 0x30a0`, which admits `0x30a2`.
- **`mesa/0003-connect-the-native-window-when-egl-is-loaded-directly.patch`** — Mesa's Android EGL
  is written to run *behind* Android's `libEGL`, whose `eglCreateWindowSurface` has already called
  `native_window_api_connect(NATIVE_WINDOW_API_EGL)` before dispatching to the driver. We dlopen it
  directly instead, so nothing claims the window and its BufferQueue answers every `dequeueBuffer`
  with `NO_INIT` — leaving the default framebuffer with no colour attachment, which Zink then
  dereferences in `begin_rendering`. `droid_create_surface` now connects and `droid_destroy_surface`
  disconnects. `native_window_api_connect` is a `perform()` inline that only Mesa's legacy
  `system/window.h` declares, and that header duplicates — incompatibly — the types
  `nativebase/nativebase.h` owns, so it is made to include its sibling instead, as AOSP's own copy
  does.

# OpenJDK

Upstream OpenJDK has no Android target. It builds against glibc, and the build script papers over
part of the gap by handing `configure` the `*-unknown-linux-gnu` triplet while pointing every tool
at the NDK — so OpenJDK configures an ordinary 64-bit Linux build and then compiles it against
bionic. These patches cover the rest.

## Verified against jdk17u, jdk21u and jdk25u source and the NDK r28 sysroot

Everything below was checked against real `openjdk/jdk17u`, `openjdk/jdk21u` and `openjdk/jdk25u`
checkouts and the bionic headers in NDK 28.2, not assumed.

`jdk17/`, `jdk21/` and `jdk25/` carry the same six patches. None could be dropped as fixed
upstream, because every one of them closes a gap on bionic's side rather than a bug on HotSpot's:
newer JDK sources cannot help when the NDK still defines no `_CS_GNU_LIBC_VERSION`, declares no
`dlinfo`, and ships no `prstatus_t`. The three copies are not shared, though — each is generated
against its own checkout so it applies at its own line numbers instead of on `patch` fuzz, which
matters most for `0003`, whose context reads `NULL` on 17 and `nullptr` on 21 and 25.

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

- **`0001-bionic-libc-version-detection.patch`** — bionic defines neither `_CS_GNU_LIBC_VERSION`
  nor `_CS_GNU_LIBPTHREAD_VERSION`, so `os::Linux::libpthread_init` trips its
  `#error "glibc too old (< 2.3.2)"` guard. Adds a `__BIONIC__` branch alongside the existing
  `MUSL_LIBC` one.
- **`0002-bionic-elf-symbol-macros.patch`** — `<linux/elf.h>` already defines `ELF_ST_TYPE`, and
  defines `ELF64_ST_TYPE` in terms of it. `elfFile.hpp` redefines `ELF_ST_TYPE` to `ELF64_ST_TYPE`,
  which is then circular: the preprocessor will not expand a macro inside its own expansion, so a
  bare identifier is left behind and the compile fails.
- **`0003-bionic-no-dlinfo.patch`** — `os::Linux::dll_path` maps a `dlopen` handle back to a path
  through `dlinfo`, which bionic does not declare. The function already has a "path unknown" answer
  for the failing case, so that is what Android returns.
- **`0004-bionic-netinet-in-include.patch`** — `SOCKETADDRESS` embeds `sockaddr_in` and
  `sockaddr_in6` by value, and glibc's `<netdb.h>` happens to pull `<netinet/in.h>` in
  transitively where bionic's does not, leaving both types incomplete.
- **`0005-disable-serviceability-agent.patch`** — `libsaproc` reads core dumps through procfs
  structures bionic has no equivalent for. It backs `jstack`, `jmap` and `jhsdb`, none of which
  apply to a phone, so it is dropped rather than ported.
- **`0006-bionic-runpath-not-rpath.patch`** — JDK-8326891 forces `DT_RPATH`, which Android's linker
  ignores entirely, so every intra-JDK dependency becomes unresolvable. `DT_RUNPATH` is the only
  tag bionic reads. Check this one on the artifact rather than the log: an earlier attempt shipped
  a runpath reading `RIGIN`, because `$ORIGIN` had been eaten by shell escaping on the way through,
  and only `readelf -d` showed it.

### Blockers found by actually running the build

These came out of real `configure` runs in the Docker image, in the order they appeared.

1. **Toolchain discarded from the environment.** `configure` prints *"Ignoring value of CC from the
   environment. Use command line variables instead"* and then silently falls back to the host gcc,
   so the NDK toolchain was never used. Fixed by passing `CC=`, `CXX=`, `AR=` and the rest as
   configure assignments rather than exports.
2. **Build compiler must match the target toolchain.** With `--with-toolchain-type=clang`, a gcc
   *build* compiler is rejected outright. Fixed by installing clang and pointing `BUILD_CC` /
   `BUILD_CXX` at it.
3. **ALSA, cups, fontconfig and X11.** `java.desktop` requires all four even under
   `--enable-headless-only`, and installing them on the build host does *not* satisfy it: this is a
   cross build, so `configure` looks inside the target sysroot given by `--with-sysroot`, where
   Android has none of them and never will. The `Dockerfile` stages their headers into the NDK
   sysroot, plus target-built stub libraries for the two that are linked rather than dlopened.
   `libjsound` therefore builds and keeps a `DT_NEEDED` on a `libasound.so` no device has, so it
   fails to load at runtime — which costs nothing, because Minecraft drives all audio through
   OpenAL and never touches `javax.sound`. `--enable-headless-only` does not stop the X11 check
   either, and that check is autoconf's `AC_PATH_X`, which ignores `--with-sysroot` and does its
   own search, so `build-jdk.sh` points it at the staged copies through `--x-includes` and
   `--x-libraries`.

   A tidier option, if `java.desktop` ever becomes troublesome for another reason, is to drop the
   sound provider outright, but that needs a patch written against the `java.desktop` makefiles
   rather than a configure flag.

### Settled by a real build

- **`-lpthread`, `-lrt`, `-ldl`** — bionic folds all three into libc, so these link flags resolve
  to nothing. An empty linker script per name satisfies `-l` without contributing a `DT_NEEDED`,
  which is what the `Dockerfile` stages. Confirmed on the artifact: `readelf -d libjvm.so` names
  only `libandroid`, `liblog`, `libm`, `libdl` and `libc`.
- **Locale and charset** — bionic has no locale database, so `nl_langinfo` yields nothing useful,
  but the initialisation path tolerates that rather than asserting. `Charset.defaultCharset()`
  answers UTF-8 on a device with the launcher's environment.

### Still expected, to be confirmed by a real build

These have not been reproduced yet, and some may prove to be non-issues like the ones above.

- **`jspawnhelper`** — `ProcessBuilder` execs a helper binary. It compiles, but Android's SELinux
  policy blocks executing files from app-writable storage on recent releases. Minecraft needs this
  only for its crash reporter, so making the failure non-fatal is preferable to making exec work.
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
