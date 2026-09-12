// Omilator JNI bridge to libretro C API.
//
// Strategy: a single JavaVM-global handle holds the loaded core + the
// JavaVM ref + a global ref to the active JniCoreController instance.
// The static C callback trampolines (env_cb, video_cb, ...) look up
// the controller via this global and call the corresponding Kotlin
// methods through JNI. Per-instance state lives on the Kotlin side.

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <cstring>
#include <cstdlib>

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
};

JavaVM* g_jvm = nullptr;
jobject g_controller_ref = nullptr;
jmethodID g_on_env_method = nullptr;
jmethodID g_on_video_method = nullptr;
jmethodID g_on_audio_batch_method = nullptr;
jmethodID g_on_audio_sample_method = nullptr;
jmethodID g_on_input_state_method = nullptr;

CoreState g_state{};

JNIEnv* attach() {
    JNIEnv* env = nullptr;
    if (g_jvm == nullptr) return nullptr;
    g_jvm->AttachCurrentThread(&env, nullptr);
    return env;
}

void detach() {
    if (g_jvm) g_jvm->DetachCurrentThread();
}

template <typename T>
T resolve(void* h, const char* name) {
    auto sym = dlsym(h, name);
    if (!sym) LOGE("Missing symbol: %s", name);
    return reinterpret_cast<T>(sym);
}

bool on_environment(unsigned cmd, void* data) {
    if (!g_controller_ref) return false;
    JNIEnv* env = attach();
    if (!env) return false;
    jboolean result = env->CallBooleanMethod(
        g_controller_ref, g_on_env_method,
        static_cast<jint>(cmd),
        reinterpret_cast<jlong>(data)
    );
    detach();
    return result == JNI_TRUE;
}

void on_video(const void* data, unsigned width, unsigned height, size_t pitch) {
    if (!g_controller_ref) return;
    JNIEnv* env = attach();
    if (!env) return;
    env->CallVoidMethod(
        g_controller_ref, g_on_video_method,
        reinterpret_cast<jlong>(data),
        static_cast<jint>(width),
        static_cast<jint>(height),
        static_cast<jlong>(pitch)
    );
    detach();
}

size_t on_audio_batch(const int16_t* data, size_t frames) {
    if (!g_controller_ref) return frames;
    JNIEnv* env = attach();
    if (!env) return frames;
    jlong result = env->CallLongMethod(
        g_controller_ref, g_on_audio_batch_method,
        reinterpret_cast<jlong>(data),
        static_cast<jlong>(frames)
    );
    detach();
    return static_cast<size_t>(result);
}

void on_audio_sample(int16_t left, int16_t right) {
    if (!g_controller_ref) return;
    JNIEnv* env = attach();
    if (!env) return;
    env->CallVoidMethod(
        g_controller_ref, g_on_audio_sample_method,
        static_cast<jshort>(left),
        static_cast<jshort>(right)
    );
    detach();
}

void on_input_poll() {
    // No-op. State is pulled on demand.
}

int16_t on_input_state(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (!g_controller_ref) return 0;
    JNIEnv* env = attach();
    if (!env) return 0;
    jshort result = env->CallShortMethod(
        g_controller_ref, g_on_input_state_method,
        static_cast<jint>(port),
        static_cast<jint>(device),
        static_cast<jint>(index),
        static_cast<jint>(id)
    );
    detach();
    return result;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_loadCoreNative(
    JNIEnv* env, jobject thiz, jstring pathJ) {
    const char* path = env->GetStringUTFChars(pathJ, nullptr);
    void* handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    env->ReleaseStringUTFChars(pathJ, path);
    if (!handle) {
        LOGE("dlopen failed: %s", dlerror());
        return JNI_FALSE;
    }
    g_state.handle = handle;
    g_state.retro_init                       = resolve<void(*)(void)>(handle, "retro_init");
    g_state.retro_deinit                     = resolve<void(*)(void)>(handle, "retro_deinit");
    g_state.retro_api_version                = resolve<unsigned(*)(void)>(handle, "retro_api_version");
    g_state.retro_get_system_info            = resolve<void(*)(struct retro_system_info*)>(handle, "retro_get_system_info");
    g_state.retro_get_system_av_info         = resolve<void(*)(struct retro_system_av_info*)>(handle, "retro_get_system_av_info");
    g_state.retro_reset                      = resolve<void(*)(void)>(handle, "retro_reset");
    g_state.retro_run                        = resolve<void(*)(void)>(handle, "retro_run");
    g_state.retro_serialize_size             = resolve<size_t(*)(void)>(handle, "retro_serialize_size");
    g_state.retro_serialize                  = resolve<bool(*)(void*, size_t)>(handle, "retro_serialize");
    g_state.retro_unserialize                = resolve<bool(*)(const void*, size_t)>(handle, "retro_unserialize");
    g_state.retro_load_game                  = resolve<bool(*)(const struct retro_game_info*)>(handle, "retro_load_game");
    g_state.retro_unload_game                = resolve<void(*)(void)>(handle, "retro_unload_game");
    g_state.retro_get_memory_data            = resolve<void*(*)(unsigned)>(handle, "retro_get_memory_data");
    g_state.retro_get_memory_size            = resolve<size_t(*)(unsigned)>(handle, "retro_get_memory_size");
    g_state.retro_set_environment            = resolve<void(*)(retro_environment_t)>(handle, "retro_set_environment");
    g_state.retro_set_video_refresh          = resolve<void(*)(retro_video_refresh_t)>(handle, "retro_set_video_refresh");
    g_state.retro_set_audio_sample           = resolve<void(*)(retro_audio_sample_t)>(handle, "retro_set_audio_sample");
    g_state.retro_set_audio_sample_batch     = resolve<void(*)(retro_audio_sample_batch_t)>(handle, "retro_set_audio_sample_batch");
    g_state.retro_set_input_poll             = resolve<void(*)(retro_input_poll_t)>(handle, "retro_set_input_poll");
    g_state.retro_set_input_state            = resolve<void(*)(retro_input_state_t)>(handle, "retro_set_input_state");

    // Cache controller ref + method IDs
    if (g_controller_ref) env->DeleteGlobalRef(g_controller_ref);
    g_controller_ref = env->NewGlobalRef(thiz);
    jclass cls = env->GetObjectClass(thiz);
    g_on_env_method           = env->GetMethodID(cls, "onEnvironment", "(IJ)Z");
    g_on_video_method         = env->GetMethodID(cls, "onVideo",    "(JIIJ)V");
    g_on_audio_batch_method   = env->GetMethodID(cls, "onAudioBatch","(JJ)J");
    g_on_audio_sample_method  = env->GetMethodID(cls, "onAudioSample","(SS)V");
    g_on_input_state_method   = env->GetMethodID(cls, "onInputState","(IIII)S");
    env->DeleteLocalRef(cls);

    // Wire callbacks
    g_state.retro_set_environment(on_environment);
    g_state.retro_set_video_refresh(on_video);
    g_state.retro_set_audio_sample(on_audio_sample);
    g_state.retro_set_audio_sample_batch(on_audio_batch);
    g_state.retro_set_input_poll(on_input_poll);
    g_state.retro_set_input_state(on_input_state);

    g_state.retro_init();
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_loadGameNative(
    JNIEnv* env, jobject thiz, jstring pathJ) {
    const char* path = env->GetStringUTFChars(pathJ, nullptr);
    struct retro_game_info info{};
    info.path = path;
    info.data = nullptr;
    info.size = 0;
    info.meta = nullptr;
    bool ok = g_state.retro_load_game(&info);
    env->ReleaseStringUTFChars(pathJ, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_runFrameNative(JNIEnv*, jobject) {
    if (g_state.retro_run) g_state.retro_run();
}

JNIEXPORT void JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_resetNative(JNIEnv*, jobject) {
    if (g_state.retro_reset) g_state.retro_reset();
}

JNIEXPORT void JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_unloadGameNative(JNIEnv*, jobject) {
    if (g_state.retro_unload_game) g_state.retro_unload_game();
}

JNIEXPORT void JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_deinitNative(JNIEnv* env, jobject) {
    if (g_state.retro_deinit) g_state.retro_deinit();
    if (g_state.handle) dlclose(g_state.handle);
    g_state = CoreState{};
    if (g_controller_ref) {
        env->DeleteGlobalRef(g_controller_ref);
        g_controller_ref = nullptr;
    }
}

JNIEXPORT jint JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_apiVersionNative(JNIEnv*, jobject) {
    return g_state.retro_api_version ? static_cast<jint>(g_state.retro_api_version()) : 0;
}

JNIEXPORT jboolean JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_saveStateNative(
    JNIEnv* env, jobject thiz, jbyteArray bytesJ, jlong size) {
    if (!g_state.retro_serialize) return JNI_FALSE;
    void* buf = malloc(static_cast<size_t>(size));
    bool ok = g_state.retro_serialize(buf, static_cast<size_t>(size));
    if (ok) {
        env->SetByteArrayRegion(bytesJ, 0, static_cast<jsize>(size), static_cast<jbyte*>(buf));
    }
    free(buf);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_loadStateNative(
    JNIEnv* env, jobject thiz, jbyteArray bytesJ, jlong size) {
    if (!g_state.retro_unserialize) return JNI_FALSE;
    void* buf = malloc(static_cast<size_t>(size));
    env->GetByteArrayRegion(bytesJ, 0, static_cast<jsize>(size), static_cast<jbyte*>(buf));
    bool ok = g_state.retro_unserialize(buf, static_cast<size_t>(size));
    free(buf);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_serializeSizeNative(JNIEnv*, jobject) {
    return g_state.retro_serialize_size
        ? static_cast<jlong>(g_state.retro_serialize_size())
        : 0;
}

JNIEXPORT jstring JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_systemInfoNameNative(
    JNIEnv* env, jobject) {
    struct retro_system_info info{};
    if (g_state.retro_get_system_info) g_state.retro_get_system_info(&info);
    return env->NewStringUTF(info.library_name ? info.library_name : "");
}

JNIEXPORT jdoubleArray JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_systemAvInfoNative(
    JNIEnv* env, jobject) {
    struct retro_system_av_info av{};
    if (g_state.retro_get_system_av_info) g_state.retro_get_system_av_info(&av);
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

JNIEXPORT void JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_writeNativeString(
    JNIEnv* env, jobject, jlong ptr, jstring s) {
    if (ptr == 0 || s == nullptr) return;
    const char* chars = env->GetStringUTFChars(s, nullptr);
    if (chars == nullptr) return;
    size_t len = strlen(chars) + 1;
    memcpy(reinterpret_cast<void*>(ptr), chars, len);
    env->ReleaseStringUTFChars(s, chars);
}

JNIEXPORT jint JNICALL
Java_com_omilator_core_libretro_impl_JniCoreController_readNativeInt(
    JNIEnv*, jobject, jlong ptr) {
    if (ptr == 0) return 0;
    return *reinterpret_cast<int*>(ptr);
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
