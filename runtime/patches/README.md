# OpenJDK patches for Android

`build-jdk.sh` applies every `*.patch` in `jdk<feature>/` to a pristine checkout with `patch -p1`
before configuring. Patches are numbered so their order is stable (`0001-…`, `0002-…`).

Upstream OpenJDK has no Android target. It builds against glibc, and the build script papers over
part of the gap by handing `configure` the `*-unknown-linux-gnu` triplet while pointing every tool
at the NDK — so OpenJDK configures an ordinary 64-bit Linux build and then compiles it against
bionic. The rest is what these patches are for.

## Known gaps between glibc and bionic

These are the places HotSpot and the JDK libraries reach for something bionic does not provide.
Each one is a patch that needs writing, and the first CI run is what turns this list into concrete
compiler errors with line numbers.

**HotSpot (`src/hotspot/os/linux`, `os_linux.cpp` and `os_linux_<arch>.cpp`)**

- `__libc_current_sigrtmin` / `__libc_current_sigrtmax` — absent. HotSpot uses them to pick its
  internal signals (`SR_signum`); bionic exposes `__SIGRTMIN` and the real-time range directly.
- glibc version probing (`os::Linux::libc_version`, `confstr(_CS_GNU_LIBC_VERSION)`) — absent, and
  the results feed assertions. Needs stubbing out to a fixed value.
- `mallinfo` / `malloc_stats` — bionic has `mallinfo` but not the glibc struct layout used by
  `os::Linux::print_process_memory_info`.
- `getpwuid_r` returns a stub entry on bionic, so `user.home` and `user.name` resolve to nothing
  useful. The launcher passes `-Duser.home` explicitly, but the initialisation path must not
  assert.
- `sysinfo`, `/proc/self/…` parsing — mostly fine, but `os::Linux::available_memory` on Android is
  better answered from cgroup limits than from `sysinfo`.
- Large pages / transparent huge pages — must be forced off; Android does not expose `hugetlbfs`.
- `dladdr`/`dl_iterate_phdr` are present since API 21 and need no change.

**Launcher and libraries**

- `-lpthread`, `-lrt`, `-ldl` — bionic folds all three into libc, so these link flags fail to
  resolve. Handled in `build-jdk.sh` by the extra ldflags, but the makefiles list them in several
  places and may need editing too.
- `jspawnhelper` (`src/java.base/unix/native/libjava/childproc.c`) — `ProcessBuilder` execs a helper
  binary. It builds, but Android's SELinux policy blocks executing files from app-writable storage
  on newer releases. Minecraft only needs this for its crash reporter, so a patch that makes the
  failure non-fatal is preferable to making exec work.
- `libnio` `epoll`/`inotify` — present in bionic; no change expected.
- `libjava` locale and charset lookup — bionic has no `nl_langinfo` locale database. The runtime
  should be pinned to UTF-8 via `-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8` at launch, and the
  initialisation path must tolerate the missing locale.

**JDK 8 specifically**

`jdk8u` predates the unified build system: HotSpot still builds from its own makefiles under
`hotspot/make`, which detect the compiler and platform separately from `configure`. It is by some
margin the hardest of the four, and it is only needed for Minecraft 1.16.5 and older
(`javaVersion.component` = `jre-legacy`). Build 17, 21 and 25 first.

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
