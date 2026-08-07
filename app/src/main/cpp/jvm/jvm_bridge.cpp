#include <dlfcn.h>
#include <jni.h>
#include <unistd.h>

#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "common/log.h"
#include "jvm/stdio_pump.h"

namespace {

// The NDK's jni.h stops at 1.6 because that is what ART implements. HotSpot understands 1.8 and
// reports a richer JNIEnv for it, so we spell the constant out rather than settling for 1.6.
constexpr jint kJniVersion18 = 0x00010008;

JavaVM* g_androidVm = nullptr;

using CreateJavaVmFn = jint (*)(JavaVM**, void**, void*);

/** Owns the UTF-8 copies of a Java `String[]` for as long as the JVM needs to see them. */
class StringArray {
public:
    StringArray(JNIEnv* env, jobjectArray array) {
        if (array == nullptr) {
            return;
        }
        const jsize length = env->GetArrayLength(array);
        values_.reserve(static_cast<size_t>(length));
        for (jsize i = 0; i < length; ++i) {
            auto element = static_cast<jstring>(env->GetObjectArrayElement(array, i));
            if (element == nullptr) {
                values_.emplace_back();
                continue;
            }
            const char* chars = env->GetStringUTFChars(element, nullptr);
            values_.emplace_back(chars == nullptr ? "" : chars);
            if (chars != nullptr) {
                env->ReleaseStringUTFChars(element, chars);
            }
            env->DeleteLocalRef(element);
        }
    }

    size_t size() const { return values_.size(); }
    const std::string& operator[](size_t index) const { return values_[index]; }

private:
    std::vector<std::string> values_;
};

/**
 * Turns `com.example.Main` into `com/example/Main`. `FindClass` speaks internal form, but every
 * source of a main class name (version manifests, mod loaders, the user) uses the dotted form.
 */
std::string toInternalName(const std::string& binaryName) {
    std::string internalName = binaryName;
    for (char& character : internalName) {
        if (character == '.') {
            character = '/';
        }
    }
    return internalName;
}

void describeAndClearException(JNIEnv* env) {
    if (!env->ExceptionCheck()) {
        return;
    }
    // Routes the stack trace to stderr, which the stdio pump has already redirected into the log.
    env->ExceptionDescribe();
    env->ExceptionClear();
}

} // namespace


extern "C" JavaVM* lodestone_android_vm() {
    return g_androidVm;
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_androidVm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_github_lodestone_runtime_JvmBridge_nativeStartStdioPump(JNIEnv* env, jclass, jobject sink) {
    return lodestone::startStdioPump(env, sink) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL Java_com_github_lodestone_runtime_JvmBridge_nativeSetEnv(
        JNIEnv* env, jclass, jstring nameString, jstring valueString) {
    const char* name = env->GetStringUTFChars(nameString, nullptr);
    const char* value = env->GetStringUTFChars(valueString, nullptr);
    if (name != nullptr && value != nullptr) {
        setenv(name, value, 1);
    }
    if (name != nullptr) {
        env->ReleaseStringUTFChars(nameString, name);
    }
    if (value != nullptr) {
        env->ReleaseStringUTFChars(valueString, value);
    }
}

/**
 * Boots a HotSpot VM from `libjvmPath` in this process and runs `mainClass.main(mainArgs)`.
 *
 * The call blocks until the game's main method returns and every non-daemon thread has finished, so
 * the caller must invoke it from a dedicated thread with a large stack — HotSpot adopts the calling
 * thread as its primordial thread, and Android's default 1 MiB is not enough for the interpreter.
 *
 * Returns 0 on a clean return, or a negative code describing where startup failed. A game that
 * calls `System.exit` terminates the process from inside the VM and never returns here.
 */
extern "C" JNIEXPORT jint JNICALL Java_com_github_lodestone_runtime_JvmBridge_nativeLaunch(
        JNIEnv* env,
        jclass,
        jstring libjvmPathString,
        jobjectArray vmArgsArray,
        jstring mainClassString,
        jobjectArray mainArgsArray) {
    const char* libjvmPathChars = env->GetStringUTFChars(libjvmPathString, nullptr);
    if (libjvmPathChars == nullptr) {
        return -1;
    }
    const std::string libjvmPath(libjvmPathChars);
    env->ReleaseStringUTFChars(libjvmPathString, libjvmPathChars);

    const char* mainClassChars = env->GetStringUTFChars(mainClassString, nullptr);
    if (mainClassChars == nullptr) {
        return -1;
    }
    const std::string mainClass(mainClassChars);
    env->ReleaseStringUTFChars(mainClassString, mainClassChars);

    const StringArray vmArgs(env, vmArgsArray);
    const StringArray mainArgs(env, mainArgsArray);

    // Opened before libjvm, and fresh rather than RTLD_NOLOAD: only a library the linker loads
    // into the global group can satisfy the JDK's references to glibc symbols bionic lacks.
    if (dlopen("liblodestone_compat.so", RTLD_NOW | RTLD_GLOBAL) == nullptr) {
        LOGW("bionic compatibility shims unavailable: %s", dlerror());
    }

    // RTLD_GLOBAL so that the runtime's own libraries (libjava, libnio, libawt) and the GLFW shim
    // resolve HotSpot's exported JNI and JVM_* entry points when the VM loads them later.
    void* libjvm = dlopen(libjvmPath.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (libjvm == nullptr) {
        LOGE("dlopen(%s) failed: %s", libjvmPath.c_str(), dlerror());
        return -2;
    }

    auto createJavaVm = reinterpret_cast<CreateJavaVmFn>(dlsym(libjvm, "JNI_CreateJavaVM"));
    if (createJavaVm == nullptr) {
        LOGE("JNI_CreateJavaVM missing from %s: %s", libjvmPath.c_str(), dlerror());
        return -3;
    }

    std::vector<JavaVMOption> options;
    options.reserve(vmArgs.size());
    for (size_t i = 0; i < vmArgs.size(); ++i) {
        options.push_back(JavaVMOption{const_cast<char*>(vmArgs[i].c_str()), nullptr});
    }

    JavaVMInitArgs initArgs{};
    initArgs.version = kJniVersion18;
    initArgs.nOptions = static_cast<jint>(options.size());
    initArgs.options = options.empty() ? nullptr : options.data();
    // Fail loudly on a bad flag rather than silently starting a VM the user did not ask for.
    initArgs.ignoreUnrecognized = JNI_FALSE;

    JavaVM* gameVm = nullptr;
    JNIEnv* gameEnv = nullptr;
    const jint createResult =
            createJavaVm(&gameVm, reinterpret_cast<void**>(&gameEnv), &initArgs);
    if (createResult != JNI_OK) {
        LOGE("JNI_CreateJavaVM failed with %d", createResult);
        return -4;
    }

    jint status = 0;
    const std::string internalName = toInternalName(mainClass);
    jclass mainClassRef = gameEnv->FindClass(internalName.c_str());
    if (mainClassRef == nullptr) {
        LOGE("main class %s not found on the classpath", mainClass.c_str());
        describeAndClearException(gameEnv);
        status = -5;
    }

    jmethodID mainMethod = nullptr;
    if (status == 0) {
        mainMethod = gameEnv->GetStaticMethodID(mainClassRef, "main", "([Ljava/lang/String;)V");
        if (mainMethod == nullptr) {
            LOGE("%s has no static void main(String[])", mainClass.c_str());
            describeAndClearException(gameEnv);
            status = -6;
        }
    }

    if (status == 0) {
        jclass stringClass = gameEnv->FindClass("java/lang/String");
        jobjectArray arguments = gameEnv->NewObjectArray(
                static_cast<jsize>(mainArgs.size()), stringClass, nullptr);
        for (size_t i = 0; i < mainArgs.size(); ++i) {
            jstring value = gameEnv->NewStringUTF(mainArgs[i].c_str());
            gameEnv->SetObjectArrayElement(arguments, static_cast<jsize>(i), value);
            gameEnv->DeleteLocalRef(value);
        }

        gameEnv->CallStaticVoidMethod(mainClassRef, mainMethod, arguments);
        if (gameEnv->ExceptionCheck()) {
            describeAndClearException(gameEnv);
            status = 1;
        }
        gameEnv->DeleteLocalRef(arguments);
        gameEnv->DeleteLocalRef(stringClass);
    }

    if (mainClassRef != nullptr) {
        gameEnv->DeleteLocalRef(mainClassRef);
    }

    // Blocks until the game's non-daemon threads finish, matching what the `java` launcher does.
    gameVm->DetachCurrentThread();
    gameVm->DestroyJavaVM();

    // stdout is a pipe, so anything the VM buffered on the way out would otherwise be lost.
    fflush(nullptr);
    return status;
}
