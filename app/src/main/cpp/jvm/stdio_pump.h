#pragma once

#include <jni.h>

namespace lodestone {

/**
 * Redirects this process's stdout and stderr into a reader thread that forwards whole lines to
 * `sink.accept(String)` on the Android VM. The Java runtime we host writes its own logging, crash
 * reports and `System.out` traffic to those descriptors, and on Android nothing reads them by
 * default, so without this every message the game prints would be discarded.
 *
 * Safe to call more than once; only the first call installs the redirect.
 */
bool startStdioPump(JNIEnv* env, jobject sink);

} // namespace lodestone
