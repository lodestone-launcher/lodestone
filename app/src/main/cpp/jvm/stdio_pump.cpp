#include "jvm/stdio_pump.h"

#include <pthread.h>
#include <unistd.h>

#include <atomic>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <string>

#include "common/log.h"

extern "C" JavaVM* lodestone_android_vm();

namespace lodestone {
namespace {

std::atomic_bool g_installed{false};

struct PumpState {
    int readFd;
    FILE* mirror = nullptr;
    jobject sink;       // global ref to a java.util.function.Consumer-shaped object
    jmethodID acceptId; // void accept(String)
};

/**
 * Emits one line to logcat and to the Java sink. Lines are forwarded individually so the in-app log
 * viewer sees output at the same granularity the game produced it.
 */
void emitLine(JNIEnv* env, PumpState* state, const char* data, size_t length) {
    if (length == 0) {
        return;
    }
    __android_log_print(ANDROID_LOG_INFO, "Minecraft", "%.*s", static_cast<int>(length), data);

    // Mirrored to a file, flushed per line. HotSpot reports a fatal startup problem by printing to
    // stderr and calling exit() immediately, which tears the process down before this thread can
    // drain the pipe — so the one message explaining the failure is exactly the message logcat
    // never receives. The file survives.
    if (state->mirror != nullptr) {
        fwrite(data, 1, length, state->mirror);
        fputc('\n', state->mirror);
        fflush(state->mirror);
    }

    std::string owned(data, length);
    jstring text = env->NewStringUTF(owned.c_str());
    if (text == nullptr) {
        // A malformed UTF-8 sequence in the game's output must not tear down the pump.
        env->ExceptionClear();
        return;
    }
    env->CallVoidMethod(state->sink, state->acceptId, text);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    env->DeleteLocalRef(text);
}

void* pumpMain(void* argument) {
    auto* state = static_cast<PumpState*>(argument);

    JavaVM* vm = lodestone_android_vm();
    JNIEnv* env = nullptr;
    JavaVMAttachArgs attachArgs{JNI_VERSION_1_6, const_cast<char*>("lodestone-stdio"), nullptr};
    if (vm == nullptr || vm->AttachCurrentThreadAsDaemon(&env, &attachArgs) != JNI_OK) {
        LOGE("stdio pump could not attach to the Android VM");
        delete state;
        return nullptr;
    }

    std::string pending;
    char buffer[4096];
    ssize_t count;
    while ((count = read(state->readFd, buffer, sizeof(buffer))) > 0) {
        pending.append(buffer, static_cast<size_t>(count));

        size_t start = 0;
        for (size_t i = 0; i < pending.size(); ++i) {
            if (pending[i] != '\n') {
                continue;
            }
            size_t end = i;
            // Tolerate CRLF, which the log4j config emits on some platforms.
            if (end > start && pending[end - 1] == '\r') {
                --end;
            }
            emitLine(env, state, pending.data() + start, end - start);
            start = i + 1;
        }
        pending.erase(0, start);

        // A runaway writer that never emits a newline must not grow the buffer without bound.
        if (pending.size() > 1u << 20) {
            emitLine(env, state, pending.data(), pending.size());
            pending.clear();
        }
    }
    if (!pending.empty()) {
        emitLine(env, state, pending.data(), pending.size());
    }

    env->DeleteGlobalRef(state->sink);
    vm->DetachCurrentThread();
    close(state->readFd);
    delete state;
    return nullptr;
}

} // namespace

bool startStdioPump(JNIEnv* env, jobject sink) {
    bool expected = false;
    if (!g_installed.compare_exchange_strong(expected, true)) {
        return true;
    }

    jclass sinkClass = env->GetObjectClass(sink);
    jmethodID acceptId = env->GetMethodID(sinkClass, "accept", "(Ljava/lang/String;)V");
    env->DeleteLocalRef(sinkClass);
    if (acceptId == nullptr) {
        g_installed.store(false);
        return false;
    }

    int fds[2];
    if (pipe(fds) != 0) {
        LOGE("pipe() failed for the stdio pump: %s", strerror(errno));
        g_installed.store(false);
        return false;
    }

    // Line buffering keeps the in-app log responsive; the JVM would otherwise block-buffer stdout
    // because it is a pipe rather than a terminal.
    setvbuf(stdout, nullptr, _IOLBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    dup2(fds[1], STDOUT_FILENO);
    dup2(fds[1], STDERR_FILENO);
    close(fds[1]);

    auto* state = new PumpState{fds[0], nullptr, env->NewGlobalRef(sink), acceptId};
    if (const char* path = getenv("LODESTONE_STDIO_LOG")) {
        state->mirror = fopen(path, "w");
    }

    pthread_t thread;
    pthread_attr_t attributes;
    pthread_attr_init(&attributes);
    pthread_attr_setdetachstate(&attributes, PTHREAD_CREATE_DETACHED);
    int result = pthread_create(&thread, &attributes, pumpMain, state);
    pthread_attr_destroy(&attributes);
    if (result != 0) {
        LOGE("could not start the stdio pump thread: %s", strerror(result));
        env->DeleteGlobalRef(state->sink);
        close(state->readFd);
        delete state;
        g_installed.store(false);
        return false;
    }
    return true;
}

} // namespace lodestone
