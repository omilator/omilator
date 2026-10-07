// Omilator JNI bridge to libretro C API.
//
// Strategy: each JniCoreController instance owns an opaque native handle
// (heap-allocated CoreState) created by createNativeState and released by
// destroyNativeState. The state carries the dlopen'd core, its function
// pointers, stable storage for pointer-valued environment outputs, and the
// global ref to the owning controller — two live controllers can no longer
// corrupt each other's native state.
//
// libretro callback pointers carry no user data, so the static C
// trampolines dispatch through a thread-local active-state pointer that
// every JNI entry point installs for the duration of its core calls. The
// only process-global mutable state left is the JavaVM*.

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <cstdarg>
#include <cstring>
#include <cstdlib>
#include <cstdio>
#include <map>
#include <string>
#include <vector>

#define LOG_TAG "OmilatorJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {
#include "libretro.h"
}

namespace {

struct CoreState {
    void* handle = nullptr;

    void (*retro_init)(void) = nullptr;
    void (*retro_deinit)(void) = nullptr;
    unsigned (*retro_api_version)(void) = nullptr;
    void (*retro_get_system_info)(struct retro_system_info*) = nullptr;
    void (*retro_get_system_av_info)(struct retro_system_av_info*) = nullptr;
    void (*retro_set_controller_port_device)(unsigned, unsigned) = nullptr;
    void (*retro_reset)(void) = nullptr;
    void (*retro_run)(void) = nullptr;
    size_t (*retro_serialize_size)(void) = nullptr;
    bool (*retro_serialize)(void*, size_t) = nullptr;
    bool (*retro_unserialize)(const void*, size_t) = nullptr;
    void (*retro_cheat_reset)(void) = nullptr;
    void (*retro_cheat_set)(unsigned, bool, const char*) = nullptr;
    bool (*retro_load_game)(const struct retro_game_info*) = nullptr;
    void (*retro_unload_game)(void) = nullptr;
    unsigned (*retro_get_region)(void) = nullptr;
    bool (*retro_load_game_special)(unsigned, const struct retro_game_info*, size_t) = nullptr;
    void* (*retro_get_memory_data)(unsigned) = nullptr;
    size_t (*retro_get_memory_size)(unsigned) = nullptr;

    void (*retro_set_environment)(retro_environment_t) = nullptr;
    void (*retro_set_video_refresh)(retro_video_refresh_t) = nullptr;
    void (*retro_set_audio_sample)(retro_audio_sample_t) = nullptr;
    void (*retro_set_audio_sample_batch)(retro_audio_sample_batch_t) = nullptr;
    void (*retro_set_input_poll)(retro_input_poll_t) = nullptr;
    void (*retro_set_input_state)(retro_input_state_t) = nullptr;

    // Stable storage for pointer-valued environment outputs. libretro cores
    // may retain the returned pointers, so they must outlive the environment
    // callback and stay valid until the core is unloaded.
    std::string system_directory;
    std::string save_directory;
    std::string core_path;

    // Stable storage for GET_VARIABLE values. std::map nodes never move, so
    // a pointer handed to the core stays valid for the state's lifetime.
    std::map<std::string, std::string> variable_values;

    // Content buffer for cores with need_fullpath == false; kept alive until
    // core unload because the core may reference it beyond retro_load_game.
    std::vector<uint8_t> game_content;

    // Owning controller: callbacks trampoline back into this instance.
    jobject controller_ref = nullptr;
    jmethodID on_env_method = nullptr;
    jmethodID on_video_method = nullptr;
    jmethodID on_audio_batch_method = nullptr;
    jmethodID on_audio_sample_method = nullptr;
    jmethodID on_input_state_method = nullptr;

    void clear_symbols() {
        handle = nullptr;
        retro_init = nullptr;
        retro_deinit = nullptr;
        retro_api_version = nullptr;
        retro_get_system_info = nullptr;
        retro_get_system_av_info = nullptr;
        retro_set_controller_port_device = nullptr;
        retro_reset = nullptr;
        retro_run = nullptr;
        retro_serialize_size = nullptr;
        retro_serialize = nullptr;
        retro_unserialize = nullptr;
        retro_cheat_reset = nullptr;
        retro_cheat_set = nullptr;
        retro_load_game = nullptr;
        retro_unload_game = nullptr;
        retro_get_region = nullptr;
        retro_load_game_special = nullptr;
        retro_get_memory_data = nullptr;
        retro_get_memory_size = nullptr;
        retro_set_environment = nullptr;
        retro_set_video_refresh = nullptr;
        retro_set_audio_sample = nullptr;
        retro_set_audio_sample_batch = nullptr;
        retro_set_input_poll = nullptr;
        retro_set_input_state = nullptr;
        system_directory.clear();
        save_directory.clear();
        core_path.clear();
        variable_values.clear();
        game_content.clear();
    }
};

JavaVM* g_jvm = nullptr;

// The core whose JNI-entered call is running on this thread. All static
// libretro trampolines resolve their controller through this pointer.
thread_local CoreState* t_active = nullptr;

struct ActiveGuard {
    explicit ActiveGuard(CoreState* s) : saved(t_active) { t_active = s; }
    ~ActiveGuard() { t_active = saved; }
    CoreState* saved;
};

CoreState* state(jlong h) { return reinterpret_cast<CoreState*>(h); }

// Attaches the calling thread if needed. Detaching a thread that was
// already attached (the JNI caller) would invalidate its JNIEnv mid-call.
JNIEnv* attach(bool* attached) {
    JNIEnv* env = nullptr;
    if (g_jvm == nullptr) return nullptr;
    if (g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
        *attached = false;
        return env;
    }
    if (g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
        *attached = true;
        return env;
    }
    return nullptr;
}

template <typename T>
T resolve(void* h, const char* name) {
    auto sym = dlsym(h, name);
    if (!sym) LOGE("Missing symbol: %s", name);
    return reinterpret_cast<T>(sym);
}

void log_proxy(enum retro_log_level level, const char* fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    int prio;
    switch (level) {
        case RETRO_LOG_DEBUG: prio = ANDROID_LOG_DEBUG; break;
        case RETRO_LOG_INFO:  prio = ANDROID_LOG_INFO;  break;
        case RETRO_LOG_WARN:  prio = ANDROID_LOG_WARN;  break;
        case RETRO_LOG_ERROR: prio = ANDROID_LOG_ERROR; break;
        default:              prio = ANDROID_LOG_INFO;  break;
    }
    __android_log_print(prio, "OmilatorCore", "%s", buf);
}

bool on_environment(unsigned cmd, void* data) {
    CoreState* s = t_active;
    if (!s) return false;

    // Pointer-valued outputs are answered in native code: the data argument
    // is a const char** (or fn-pointer storage) the frontend must point at
    // stable storage. Copying string bytes into it overwrites adjacent
    // memory and hands the core a bogus pointer.
    switch (cmd) {
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
            if (!data) return false;
            *reinterpret_cast<const char**>(data) =
                s->system_directory.c_str();
            return true;

        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            if (!data) return false;
            *reinterpret_cast<const char**>(data) =
                s->save_directory.c_str();
            return true;

        case RETRO_ENVIRONMENT_GET_LIBRETRO_PATH:
            if (!data) return false;
            if (s->core_path.empty()) return false;
            *reinterpret_cast<const char**>(data) =
                s->core_path.c_str();
            return true;

        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: {
            // retro_log_callback { retro_log_printf_t log; } — the function
            // pointer goes at offset 0 of data. log_proxy is a plain C
            // variadic function, so this is safe without any JNI hop.
            if (!data) return false;
            *reinterpret_cast<retro_log_printf_t*>(data) = log_proxy;
            return true;
        }

        default:
            break;
    }

    if (!s->controller_ref) return false;
    bool attached = false;
    JNIEnv* env = attach(&attached);
    if (!env) return false;
    jboolean result = env->CallBooleanMethod(
        s->controller_ref, s->on_env_method,
        static_cast<jint>(cmd),
        reinterpret_cast<jlong>(data)
    );
    if (attached) g_jvm->DetachCurrentThread();
    return result == JNI_TRUE;
}

void on_video(const void* data, unsigned width, unsigned height, size_t pitch) {
    CoreState* s = t_active;
    if (!s || !s->controller_ref) return;
    bool attached = false;
    JNIEnv* env = attach(&attached);
    if (!env) return;
    env->CallVoidMethod(
        s->controller_ref, s->on_video_method,
        reinterpret_cast<jlong>(data),
        static_cast<jint>(width),
        static_cast<jint>(height),
        static_cast<jlong>(pitch)
    );
    if (attached) g_jvm->DetachCurrentThread();
}

size_t on_audio_batch(const int16_t* data, size_t frames) {
    CoreState* s = t_active;
    if (!s || !s->controller_ref) return frames;
    bool attached = false;
    JNIEnv* env = attach(&attached);
    if (!env) return frames;
    jlong result = env->CallLongMethod(
        s->controller_ref, s->on_audio_batch_method,
        reinterpret_cast<jlong>(data),
        static_cast<jlong>(frames)
    );
    if (attached) g_jvm->DetachCurrentThread();
    return static_cast<size_t>(result);
}

void on_audio_sample(int16_t left, int16_t right) {
    CoreState* s = t_active;
    if (!s || !s->controller_ref) return;
    bool attached = false;
    JNIEnv* env = attach(&attached);
    if (!env) return;
    env->CallVoidMethod(
        s->controller_ref, s->on_audio_sample_method,
        static_cast<jshort>(left),
        static_cast<jshort>(right)
    );
    if (attached) g_jvm->DetachCurrentThread();
}

void on_input_poll() {
    // No-op. State is pulled on demand.
}

int16_t on_input_state(unsigned port, unsigned device, unsigned index, unsigned id) {
    CoreState* s = t_active;
    if (!s || !s->controller_ref) return 0;
    bool attached = false;
    JNIEnv* env = attach(&attached);
    if (!env) return 0;
    jshort result = env->CallShortMethod(
        s->controller_ref, s->on_input_state_method,
        static_cast<jint>(port),
        static_cast<jint>(device),
        static_cast<jint>(index),
        static_cast<jint>(id)
    );
    if (attached) g_jvm->DetachCurrentThread();
    return result;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_createNativeState(
    JNIEnv* env, jobject thiz) {
    CoreState* s = new CoreState();
    s->controller_ref = env->NewGlobalRef(thiz);
    jclass cls = env->GetObjectClass(thiz);
    s->on_env_method           = env->GetMethodID(cls, "onEnvironment", "(IJ)Z");
    s->on_video_method         = env->GetMethodID(cls, "onVideo",    "(JIIJ)V");
    s->on_audio_batch_method   = env->GetMethodID(cls, "onAudioBatch","(JJ)J");
    s->on_audio_sample_method  = env->GetMethodID(cls, "onAudioSample","(SS)V");
    s->on_input_state_method   = env->GetMethodID(cls, "onInputState","(IIII)S");
    env->DeleteLocalRef(cls);
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_destroyNativeState(
    JNIEnv* env, jobject, jlong h) {
    CoreState* s = state(h);
    if (!s) return;
    if (s->controller_ref) {
        env->DeleteGlobalRef(s->controller_ref);
        s->controller_ref = nullptr;
    }
    delete s;
}

JNIEXPORT jboolean JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_loadCoreNative(
    JNIEnv* env, jobject, jlong h, jstring pathJ) {
    CoreState* s = state(h);
    if (!s) return JNI_FALSE;
    const char* path = env->GetStringUTFChars(pathJ, nullptr);
    void* handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    env->ReleaseStringUTFChars(pathJ, path);
    if (!handle) {
        LOGE("dlopen failed: %s", dlerror());
        return JNI_FALSE;
    }
    s->handle = handle;
    s->retro_init                       = resolve<void(*)(void)>(handle, "retro_init");
    s->retro_deinit                     = resolve<void(*)(void)>(handle, "retro_deinit");
    s->retro_api_version                = resolve<unsigned(*)(void)>(handle, "retro_api_version");
    s->retro_get_system_info            = resolve<void(*)(struct retro_system_info*)>(handle, "retro_get_system_info");
    s->retro_get_system_av_info         = resolve<void(*)(struct retro_system_av_info*)>(handle, "retro_get_system_av_info");
    s->retro_reset                      = resolve<void(*)(void)>(handle, "retro_reset");
    s->retro_run                        = resolve<void(*)(void)>(handle, "retro_run");
    s->retro_serialize_size             = resolve<size_t(*)(void)>(handle, "retro_serialize_size");
    s->retro_serialize                  = resolve<bool(*)(void*, size_t)>(handle, "retro_serialize");
    s->retro_unserialize                = resolve<bool(*)(const void*, size_t)>(handle, "retro_unserialize");
    s->retro_load_game                  = resolve<bool(*)(const struct retro_game_info*)>(handle, "retro_load_game");
    s->retro_unload_game                = resolve<void(*)(void)>(handle, "retro_unload_game");
    s->retro_get_memory_data            = resolve<void*(*)(unsigned)>(handle, "retro_get_memory_data");
    s->retro_get_memory_size            = resolve<size_t(*)(unsigned)>(handle, "retro_get_memory_size");
    s->retro_set_environment            = resolve<void(*)(retro_environment_t)>(handle, "retro_set_environment");
    s->retro_set_video_refresh          = resolve<void(*)(retro_video_refresh_t)>(handle, "retro_set_video_refresh");
    s->retro_set_audio_sample           = resolve<void(*)(retro_audio_sample_t)>(handle, "retro_set_audio_sample");
    s->retro_set_audio_sample_batch     = resolve<void(*)(retro_audio_sample_batch_t)>(handle, "retro_set_audio_sample_batch");
    s->retro_set_input_poll             = resolve<void(*)(retro_input_poll_t)>(handle, "retro_set_input_poll");
    s->retro_set_input_state            = resolve<void(*)(retro_input_state_t)>(handle, "retro_set_input_state");

    // Wire callbacks. Cores may call the environment callback during
    // registration and retro_init, so the active guard spans both.
    ActiveGuard guard(s);
    s->retro_set_environment(on_environment);
    s->retro_set_video_refresh(on_video);
    s->retro_set_audio_sample(on_audio_sample);
    s->retro_set_audio_sample_batch(on_audio_batch);
    s->retro_set_input_poll(on_input_poll);
    s->retro_set_input_state(on_input_state);

    s->retro_init();
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_setEnvPathsNative(
    JNIEnv* env, jobject, jlong h, jstring systemDirJ, jstring saveDirJ, jstring corePathJ) {
    CoreState* s = state(h);
    if (!s) return;
    if (systemDirJ) {
        const char* p = env->GetStringUTFChars(systemDirJ, nullptr);
        if (p) { s->system_directory = p; env->ReleaseStringUTFChars(systemDirJ, p); }
    }
    if (saveDirJ) {
        const char* p = env->GetStringUTFChars(saveDirJ, nullptr);
        if (p) { s->save_directory = p; env->ReleaseStringUTFChars(saveDirJ, p); }
    }
    if (corePathJ) {
        const char* p = env->GetStringUTFChars(corePathJ, nullptr);
        if (p) { s->core_path = p; env->ReleaseStringUTFChars(corePathJ, p); }
    }
}

JNIEXPORT jboolean JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_loadGameNative(
    JNIEnv* env, jobject, jlong h, jstring pathJ) {
    CoreState* s = state(h);
    if (!s || !s->retro_load_game) return JNI_FALSE;
    ActiveGuard guard(s);
    const char* path = env->GetStringUTFChars(pathJ, nullptr);
    struct retro_system_info info{};
    if (s->retro_get_system_info) s->retro_get_system_info(&info);
    struct retro_game_info gi{};
    gi.path = path;
    gi.meta = nullptr;
    if (!info.need_fullpath) {
        // The core expects in-memory content: read the file and keep the
        // buffer alive until core unload.
        FILE* f = fopen(path, "rb");
        if (f) {
            fseek(f, 0, SEEK_END);
            long sz = ftell(f);
            fseek(f, 0, SEEK_SET);
            if (sz > 0) {
                s->game_content.resize(static_cast<size_t>(sz));
                size_t rd = fread(s->game_content.data(), 1,
                                  static_cast<size_t>(sz), f);
                s->game_content.resize(rd);
                if (!s->game_content.empty()) {
                    gi.data = s->game_content.data();
                    gi.size = s->game_content.size();
                }
            }
            fclose(f);
        }
    }
    bool ok = s->retro_load_game(&gi);
    env->ReleaseStringUTFChars(pathJ, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_runFrameNative(JNIEnv*, jobject, jlong h) {
    CoreState* s = state(h);
    if (!s || !s->retro_run) return;
    ActiveGuard guard(s);
    s->retro_run();
}

JNIEXPORT void JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_resetNative(JNIEnv*, jobject, jlong h) {
    CoreState* s = state(h);
    if (!s || !s->retro_reset) return;
    ActiveGuard guard(s);
    s->retro_reset();
}

JNIEXPORT void JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_unloadGameNative(JNIEnv*, jobject, jlong h) {
    CoreState* s = state(h);
    if (!s || !s->retro_unload_game) return;
    ActiveGuard guard(s);
    s->retro_unload_game();
}

JNIEXPORT void JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_deinitNative(JNIEnv*, jobject, jlong h) {
    CoreState* s = state(h);
    if (!s) return;
    ActiveGuard guard(s);
    if (s->retro_deinit) s->retro_deinit();
    if (s->handle) dlclose(s->handle);
    s->clear_symbols();
}

JNIEXPORT jint JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_apiVersionNative(JNIEnv*, jobject, jlong h) {
    CoreState* s = state(h);
    if (!s || !s->retro_api_version) return 0;
    ActiveGuard guard(s);
    return static_cast<jint>(s->retro_api_version());
}

JNIEXPORT jboolean JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_saveStateNative(
    JNIEnv* env, jobject, jlong h, jbyteArray bytesJ, jlong size) {
    CoreState* s = state(h);
    if (!s || !s->retro_serialize) return JNI_FALSE;
    ActiveGuard guard(s);
    void* buf = malloc(static_cast<size_t>(size));
    bool ok = s->retro_serialize(buf, static_cast<size_t>(size));
    if (ok) {
        env->SetByteArrayRegion(bytesJ, 0, static_cast<jsize>(size), static_cast<jbyte*>(buf));
    }
    free(buf);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_loadStateNative(
    JNIEnv* env, jobject, jlong h, jbyteArray bytesJ, jlong size) {
    CoreState* s = state(h);
    if (!s || !s->retro_unserialize) return JNI_FALSE;
    ActiveGuard guard(s);
    void* buf = malloc(static_cast<size_t>(size));
    env->GetByteArrayRegion(bytesJ, 0, static_cast<jsize>(size), static_cast<jbyte*>(buf));
    bool ok = s->retro_unserialize(buf, static_cast<size_t>(size));
    free(buf);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_serializeSizeNative(JNIEnv*, jobject, jlong h) {
    CoreState* s = state(h);
    if (!s || !s->retro_serialize_size) return 0;
    ActiveGuard guard(s);
    return static_cast<jlong>(s->retro_serialize_size());
}

JNIEXPORT jbyteArray JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_readSaveRamNative(
    JNIEnv* env, jobject, jlong h) {
    CoreState* s = state(h);
    if (!s || !s->retro_get_memory_data || !s->retro_get_memory_size) {
        return env->NewByteArray(0);
    }
    ActiveGuard guard(s);
    void* data = s->retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
    size_t size = s->retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (!data || size == 0) return env->NewByteArray(0);
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(size));
    if (arr) {
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(size),
                                static_cast<jbyte*>(data));
    }
    return arr;
}

JNIEXPORT void JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_writeSaveRamNative(
    JNIEnv* env, jobject, jlong h, jbyteArray bytesJ) {
    CoreState* s = state(h);
    if (!s || !bytesJ || !s->retro_get_memory_data || !s->retro_get_memory_size) return;
    ActiveGuard guard(s);
    void* data = s->retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
    size_t size = s->retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (!data || size == 0) return;
    jsize len = env->GetArrayLength(bytesJ);
    if (len <= 0) return;
    // All-or-nothing, matching desktop semantics: the old partial prefix
    // copy (min(len, size) bytes) left the core running with a HALF-restored
    // battery block on a size mismatch while the read-back gate still
    // refused to flush — corrupt save view plus no persistence. No-op on
    // mismatch instead; the Kotlin-side gate handles migration.
    if (static_cast<size_t>(len) != size) return;
    env->GetByteArrayRegion(bytesJ, 0, len, static_cast<jbyte*>(data));
}

JNIEXPORT jstring JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_systemInfoNameNative(
    JNIEnv* env, jobject, jlong h) {
    CoreState* s = state(h);
    if (!s || !s->retro_get_system_info) return env->NewStringUTF("");
    ActiveGuard guard(s);
    struct retro_system_info info{};
    s->retro_get_system_info(&info);
    return env->NewStringUTF(info.library_name ? info.library_name : "");
}

JNIEXPORT jstring JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_systemInfoVersionNative(
    JNIEnv* env, jobject, jlong h) {
    CoreState* s = state(h);
    if (!s || !s->retro_get_system_info) return env->NewStringUTF("");
    ActiveGuard guard(s);
    struct retro_system_info info{};
    s->retro_get_system_info(&info);
    return env->NewStringUTF(info.library_version ? info.library_version : "");
}

JNIEXPORT jstring JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_systemInfoExtensionsNative(
    JNIEnv* env, jobject, jlong h) {
    CoreState* s = state(h);
    if (!s || !s->retro_get_system_info) return env->NewStringUTF("");
    ActiveGuard guard(s);
    struct retro_system_info info{};
    s->retro_get_system_info(&info);
    return env->NewStringUTF(info.valid_extensions ? info.valid_extensions : "");
}

JNIEXPORT jboolean JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_systemInfoNeedFullpathNative(
    JNIEnv*, jobject, jlong h) {
    CoreState* s = state(h);
    if (!s || !s->retro_get_system_info) return JNI_FALSE;
    ActiveGuard guard(s);
    struct retro_system_info info{};
    s->retro_get_system_info(&info);
    return info.need_fullpath ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_systemInfoBlockExtractNative(
    JNIEnv*, jobject, jlong h) {
    CoreState* s = state(h);
    if (!s || !s->retro_get_system_info) return JNI_FALSE;
    ActiveGuard guard(s);
    struct retro_system_info info{};
    s->retro_get_system_info(&info);
    return info.block_extract ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jdoubleArray JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_systemAvInfoNative(
    JNIEnv* env, jobject, jlong h) {
    CoreState* s = state(h);
    struct retro_system_av_info av{};
    if (s && s->retro_get_system_av_info) {
        ActiveGuard guard(s);
        s->retro_get_system_av_info(&av);
    }
    jdouble out[7] = {
        (double)av.geometry.base_width,
        (double)av.geometry.base_height,
        (double)av.geometry.max_width,
        (double)av.geometry.max_height,
        (double)av.geometry.aspect_ratio,
        av.timing.fps,
        av.timing.sample_rate,
    };
    jdoubleArray arr = env->NewDoubleArray(7);
    if (arr) env->SetDoubleArrayRegion(arr, 0, 7, out);
    return arr;
}

JNIEXPORT jint JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_readNativeInt(
    JNIEnv*, jobject, jlong ptr) {
    if (ptr == 0) return 0;
    return *reinterpret_cast<int*>(ptr);
}

JNIEXPORT void JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_writeNativeInt(
    JNIEnv*, jobject, jlong ptr, jint value) {
    if (ptr == 0) return;
    *reinterpret_cast<int*>(ptr) = value;
}

JNIEXPORT void JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_writeNativeByte(
    JNIEnv*, jobject, jlong ptr, jbyte value) {
    if (ptr == 0) return;
    *reinterpret_cast<signed char*>(ptr) = value;
}

JNIEXPORT jlong JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_readNativeLong(
    JNIEnv*, jobject, jlong ptr) {
    if (ptr == 0) return 0;
    return *reinterpret_cast<long*>(ptr);
}

JNIEXPORT jfloat JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_readNativeFloat(
    JNIEnv*, jobject, jlong ptr) {
    if (ptr == 0) return 0.0f;
    return *reinterpret_cast<float*>(ptr);
}

JNIEXPORT jdouble JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_readNativeDouble(
    JNIEnv*, jobject, jlong ptr) {
    if (ptr == 0) return 0.0;
    return *reinterpret_cast<double*>(ptr);
}

JNIEXPORT jstring JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_readNativeCString(
    JNIEnv* env, jobject, jlong ptr) {
    if (ptr == 0) return env->NewStringUTF("");
    const char* s = reinterpret_cast<const char*>(ptr);
    size_t len = strnlen(s, 4096);
    char buf[4097];
    memcpy(buf, s, len);
    buf[len] = '\0';
    return env->NewStringUTF(buf);
}

JNIEXPORT void JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_installNativeString(
    JNIEnv* env, jobject, jlong h, jlong ptr, jstring valueJ) {
    CoreState* s = state(h);
    if (!s || ptr == 0 || !valueJ) return;
    const char* value = env->GetStringUTFChars(valueJ, nullptr);
    if (!value) return;
    // Keyed by the value itself: a repeat install maps to the same stable
    // node and rewrites identical content, so pointers already handed to
    // the core stay valid.
    std::string& stored = s->variable_values[value];
    stored.assign(value);
    env->ReleaseStringUTFChars(valueJ, value);
    *reinterpret_cast<const char**>(ptr) = stored.c_str();
}

JNIEXPORT void JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_clearNativePtr(
    JNIEnv*, jobject, jlong ptr) {
    if (ptr == 0) return;
    *reinterpret_cast<void**>(ptr) = nullptr;
}

JNIEXPORT void JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_copyNativeBytes(
    JNIEnv* env, jobject, jlong ptr, jbyteArray dest, jint size) {
    if (ptr == 0 || size <= 0) return;
    env->SetByteArrayRegion(dest, 0, size, reinterpret_cast<jbyte*>(ptr));
}

JNIEXPORT void JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_copyNativeShorts(
    JNIEnv* env, jobject, jlong ptr, jshortArray dest, jint size) {
    if (ptr == 0 || size <= 0) return;
    env->SetShortArrayRegion(dest, 0, size, reinterpret_cast<jshort*>(ptr));
}

}  // extern "C"
