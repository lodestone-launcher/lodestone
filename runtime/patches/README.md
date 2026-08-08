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

## JDK 8 — a different patch set entirely

`jdk8/` shares none of its fourteen patches with the other three, and not because the same problems
were spelled differently: only two of the six recur at all. jdk8u predates both the unified build
system and the musl portability work, so the modern set's `dlinfo`, `netinet/in.h` and
`--disable-new-dtags` problems simply are not in this source, and a different set is.

Verified against `openjdk/jdk8u` at `aa3f9dea`, which is what the published tarball was built from.
All fourteen apply at zero offset and zero fuzz.

Two things make 8 structurally different from 17/21/25:

- **HotSpot builds from `hotspot/make`, not from `configure`.** Its makefiles derive their own
  flags, and `configure` feeds them a `LEGACY_*` set. That set is where the first patch lives.
- **`configure` is a checked-in `generated-configure.sh`**, so any `.m4` change has to be mirrored
  into the generated script. The wrapper only regenerates when `hg` is on `PATH`, which it is not in
  the build image, so the two are edited together and stay consistent.

### Patches written for 8

- **`0001-hotspot-host-tools-target-flags.patch`** — `flags.m4` appends `--with-extra-*` to both
  `LEGACY_TARGET_*` and `LEGACY_HOST_*`. The host set compiles `adlc` and the JVMTI and JFR
  generators, which run on the build machine, so on a cross build `adlc` is linked with `-landroid
  -llog` and the host linker has no such libraries. Restricted to non-cross builds.
- **`0002-clang-assembler-prfum.patch`** — `copy_linux_aarch64.s` writes `prfm pldl1keep, [s, #-256]`.
  PRFM's scaled immediate encodes no negative offset; GNU as silently rewrites it to the unscaled
  form, and clang's integrated assembler rejects it. Spelled `prfum`.
- **`0003-bionic-elf-symbol-macros.patch`** — the same circular `ELF_ST_TYPE` expansion as the
  modern set's `0002`, in `hotspot/src/share/vm/utilities/elfFile.hpp`.
- **`0004-bionic-sigcld-alias.patch`** — bionic omits the System V `SIGCLD` alias, leaving
  `siglabels[]` an incomplete type. Defined to `SIGCHLD`, so `sun.misc.Signal("CLD")` keeps working.
- **`0005-bionic-libc-version-detection.patch`** — 8's `libpthread_init` is the pre-musl version: it
  calls `confstr`, which bionic does not have at all, and falls back to `gnu_get_libc_version` from
  `<gnu/libc-version.h>`, which bionic also does not have. A `__BIONIC__` branch names the libc and
  declares NPTL semantics, which is what the surrounding floating-stack logic actually tests for.
  Also drops the `<fpu_control.h>` include from `os_linux_aarch64.cpp`, where every FPU control-word
  entry point is already an empty stub.
- **`0006-disable-serviceability-agent-native.patch`** — as on the modern releases, but 8 has no
  `INCLUDE_SA`: the gate is `BUILDLIBSAPROC` in `saproc.make` plus `ADD_SA_BINARIES/aarch64` in
  `defs.make`. The Java half still builds.
- **`0007-bionic-no-values-h.patch`** — `net_util_md.c` includes glibc's legacy `<values.h>` for
  `MAXINT` alone.
- **`0008-no-warnings-as-errors-libsctp.patch`** — 8 has no `--disable-warnings-as-errors`, and
  `libsctp` is the one place it promotes warnings to errors.
- **`0009-bionic-no-confstr.patch`** — `LinuxVirtualMachine.isLinuxThreads` calls `confstr`. On
  bionic the answer is always "no".
- **`0010-honour-disable-headful.patch`** — `--disable-headful` printed *"headless only"* and then
  built `libawt_xawt`, the GTK peers and `libsplashscreen` anyway: `configure` never defined
  `BUILD_HEADLESS_ONLY`, and `CompileJavaClasses.gmk` still carries the *"TODO: Add
  BUILD_HEADLESS_ONLY to configure?"* that says so. Every consumer was already written; only the
  definition was missing, and `spec.gmk.in` can derive it from the `SUPPORT_HEADFUL` it already
  substitutes. Without this the build reaches `XToolkit.c` and its `backtrace` from `<execinfo.h>`.
- **`0011-bionic-xsi-strerror-r.patch`** — `jni_util_md.c` reaches past glibc's GNU `strerror_r` to
  the XSI `__xpg_strerror_r`, so that `getErrorString` can return an `int`. bionic gives
  `strerror_r` GNU semantics too but exposes no second entry point, so `getErrorString` copies the
  returned string into the caller's buffer instead.
- **`0012-no-alsa-sound-provider.patch`** — `libjsoundalsa` is the one library in the JDK that links
  ALSA's symbols rather than dlopening them, so the Dockerfile's stub cannot satisfy it. Dropping it
  from `EXTRA_SOUND_JNI_LIBS` removes it from both the build and what
  `com.sun.media.sound.Platform` loads. The portable `libjsound` stays and reports no mixers.
- **`0013-headless-jawt.patch`** — `JAWT_GetAWT` only returns "no AWT here" when `JAVASE_EMBEDDED`
  *and* `HEADLESS` are set, so any other headless build links `libjawt` against X11 entry points
  that are not there. Headless alone is the condition that matters.
- **`0014-instantiate-arrayallocator-free.patch`** — the one patch here that closes a
  clang-versus-gcc gap rather than a bionic one, and the reason the first published Java 8 runtime
  could not be loaded at all. `bitset.cpp` holds a `BitMap` by value but includes only
  `allocation.hpp`, so `~BitSet` compiles a call to `ArrayAllocator<bm_word_t, mtInternal>::free()`
  it has no definition for. Every translation unit that *does* include `allocation.inline.hpp`
  inlines the body at each call, and clang then drops the unreferenced `linkonce_odr` copy where
  gcc keeps it — so with gcc some object happens to carry the definition and with clang no object
  does. Nothing failed: shared libraries may leave symbols undefined, and the reference was an
  `R_AARCH64_JUMP_SLOT`, which on glibc would at worst be a lazy binding that never happens. bionic
  has no lazy PLT binding, so it is resolved at `dlopen` and the whole VM fails to open. Adding the
  include to `bitset.cpp` is enough; `RTLD_LAZY` is not a workaround.

### What 8 does *not* need

- **`DT_RUNPATH`** — 8 emits `-Wl,-rpath,$$ORIGIN` with no `--disable-new-dtags`, so lld's default
  new dtags apply and the tag is already `DT_RUNPATH`. Confirmed on the artifact:
  `readelf -d libnio.so` reads `RUNPATH [$ORIGIN]`, with a literal `$`.
- **`--with-freetype=bundled`** — works, because jdk8u carries freetype sources at
  `jdk/src/share/native/sun/awt/libfreetype` and builds `libfreetype.so` for the target.
- **`dlinfo` and `netinet/in.h`** — 8 calls neither; `net_util_md.h` already includes the header.

### `libc++_shared.so`

8's `libjvm.so` carries a `DT_NEEDED` on `libc++_shared.so`, which 17, 21 and 25 do not: modern
HotSpot links its C++ runtime statically, while 8's `hotspot/make` sets `STATIC_CXX` only for gcc.
Nothing is shipped inside the runtime for it. The APK already contains `libc++_shared.so`, and
`liblodestone_jvm.so` — the library that `dlopen`s `libjvm.so` — links it, so it is loaded under
that soname before HotSpot is opened.

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
