// JNI bridge for org.thortracker.audio.ChipAudio. Every call except nativePosition, nativeStats, nativeOpl3Vgm,
// nativeYmExport and nativeSmsVgm must come from ChipAudio's single control thread.
#include <jni.h>

#include <string>

#include "audio_engine.h"
#include "opl3/opl3_export.h"
#include "ym/ym_export.h"
#include "sms/sms_export.h"

using thortracker::Engine;

namespace {
Engine* engine(jlong handle) { return reinterpret_cast<Engine*>(handle); }

// UTF-8 bytes (Kotlin's encodeToByteArray: real UTF-8, unlike JNI's modified UTF-8) as a C string.
std::string utf8(JNIEnv* env, jbyteArray bytes) {
    std::string s(static_cast<size_t>(env->GetArrayLength(bytes)), '\0');
    env->GetByteArrayRegion(bytes, 0, static_cast<jsize>(s.size()), reinterpret_cast<jbyte*>(&s[0]));
    return s;
}
}  // namespace

extern "C" {

// 0 if the system id has no engine in this build or allocation fails.
JNIEXPORT jlong JNICALL Java_org_thortracker_audio_ChipAudio_nativeCreate(JNIEnv* env, jclass, jstring system) {
    const char* id = env->GetStringUTFChars(system, nullptr);
    if (!id) return 0;
    Engine* e = Engine::create(chip_engine_ops(id));
    env->ReleaseStringUTFChars(system, id);
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT void JNICALL Java_org_thortracker_audio_ChipAudio_nativeDestroy(JNIEnv*, jclass, jlong h) {
    delete engine(h);
}

JNIEXPORT jint JNICALL Java_org_thortracker_audio_ChipAudio_nativeSetImage(JNIEnv* env, jclass, jlong h,
                                                                          jbyteArray image, jint version) {
    jbyte* bytes = env->GetByteArrayElements(image, nullptr);
    if (!bytes) return CHIP_ERR_IMAGE;
    const int result = engine(h)->set_image(reinterpret_cast<const uint8_t*>(bytes),
                                            static_cast<size_t>(env->GetArrayLength(image)),
                                            static_cast<uint32_t>(version));
    env->ReleaseByteArrayElements(image, bytes, JNI_ABORT);
    return result;
}

JNIEXPORT jint JNICALL Java_org_thortracker_audio_ChipAudio_nativeEnsureStream(JNIEnv*, jclass, jlong h) {
    return engine(h)->ensure_stream();
}

JNIEXPORT jint JNICALL Java_org_thortracker_audio_ChipAudio_nativePlay(JNIEnv*, jclass, jlong h, jint order,
                                                                      jint row, jint max_ticks) {
    return engine(h)->play(order, row, max_ticks);
}

JNIEXPORT void JNICALL Java_org_thortracker_audio_ChipAudio_nativeStop(JNIEnv*, jclass, jlong h) {
    engine(h)->stop();
}

JNIEXPORT jint JNICALL Java_org_thortracker_audio_ChipAudio_nativeAudition(JNIEnv*, jclass, jlong h, jint channel,
                                                                          jint note, jint instrument, jint fine) {
    return engine(h)->audition(channel, note, instrument, fine);
}

JNIEXPORT void JNICALL Java_org_thortracker_audio_ChipAudio_nativeReleaseAudition(JNIEnv*, jclass, jlong h) {
    engine(h)->release_audition();
}

JNIEXPORT jboolean JNICALL Java_org_thortracker_audio_ChipAudio_nativeSuspendIfIdle(JNIEnv*, jclass, jlong h) {
    return engine(h)->suspend_if_idle() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL Java_org_thortracker_audio_ChipAudio_nativePosition(JNIEnv*, jclass, jlong h) {
    return engine(h)->position();
}

JNIEXPORT void JNICALL Java_org_thortracker_audio_ChipAudio_nativeStats(JNIEnv* env, jclass, jlong h,
                                                                       jlongArray out) {
    int64_t values[Engine::kStatCount] = {};
    engine(h)->stats(values, Engine::kStatCount);
    const jsize length = env->GetArrayLength(out);
    const jsize n = length < Engine::kStatCount ? length : Engine::kStatCount;
    jlong tmp[Engine::kStatCount];
    for (int i = 0; i < Engine::kStatCount; ++i) tmp[i] = static_cast<jlong>(values[i]);
    env->SetLongArrayRegion(out, 0, n, tmp);
}

// The OPL3 VGM export (any thread; allocates, renders the whole song: not the audio thread). Returns the file, or
// throws IllegalArgumentException with the loader's reason for a rejected image.
JNIEXPORT jbyteArray JNICALL Java_org_thortracker_audio_ChipAudio_nativeOpl3Vgm(JNIEnv* env, jclass, jbyteArray image,
                                                                               jint max_ticks, jbyteArray title,
                                                                               jbyteArray author, jbyteArray date) {
    const std::string t = utf8(env, title), a = utf8(env, author), d = utf8(env, date);
    jbyte* bytes = env->GetByteArrayElements(image, nullptr);
    if (!bytes) return nullptr;  // OutOfMemoryError pending
    size_t size = 0;
    const char* problem = nullptr;
    uint8_t* vgm = o3_export_vgm(reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(env->GetArrayLength(image)),
                                 static_cast<uint32_t>(max_ticks < 0 ? 0 : max_ticks), t.c_str(), a.c_str(), d.c_str(),
                                 &size, &problem);
    env->ReleaseByteArrayElements(image, bytes, JNI_ABORT);
    if (!vgm) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), problem ? problem : "VGM export failed");
        return nullptr;
    }
    jbyteArray out = env->NewByteArray(static_cast<jsize>(size));
    if (out) env->SetByteArrayRegion(out, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte*>(vgm));
    o3_export_free(vgm);
    return out;
}

// The YM2612 exports (any thread; allocates, renders the whole song: not the audio thread): format 0 VGM, 1 XGM, 2 XGC
// (ym_export.h). Kotlin: `@JvmStatic private external fun nativeYmExport(image: ByteArray, format: Int, maxTicks: Int,
// title: ByteArray, author: ByteArray, date: ByteArray): ByteArray`. Returns the file, or throws
// IllegalArgumentException with the loader's reason for a rejected image.
JNIEXPORT jbyteArray JNICALL Java_org_thortracker_audio_ChipAudio_nativeYmExport(JNIEnv* env, jclass, jbyteArray image,
                                                                                jint format, jint max_ticks,
                                                                                jbyteArray title, jbyteArray author,
                                                                                jbyteArray date) {
    const std::string t = utf8(env, title), a = utf8(env, author), d = utf8(env, date);
    jbyte* bytes = env->GetByteArrayElements(image, nullptr);
    if (!bytes) return nullptr;  // OutOfMemoryError pending
    size_t size = 0;
    const char* problem = nullptr;
    uint8_t* file = ym_export(reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(env->GetArrayLength(image)),
                              static_cast<int>(format), static_cast<uint32_t>(max_ticks < 0 ? 0 : max_ticks), t.c_str(),
                              a.c_str(), d.c_str(), &size, &problem);
    env->ReleaseByteArrayElements(image, bytes, JNI_ABORT);
    if (!file) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), problem ? problem : "YM2612 export failed");
        return nullptr;
    }
    jbyteArray out = env->NewByteArray(static_cast<jsize>(size));
    if (out) env->SetByteArrayRegion(out, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte*>(file));
    ym_export_free(file);
    return out;
}

// The Master System VGM export (any thread; allocates, renders the whole song: not the audio thread; sms_export.h).
// Kotlin: `@JvmStatic private external fun nativeSmsVgm(image: ByteArray, maxTicks: Int, title: ByteArray,
// author: ByteArray, date: ByteArray): ByteArray`. Returns the file, or throws IllegalArgumentException with the
// loader's reason for a rejected image.
JNIEXPORT jbyteArray JNICALL Java_org_thortracker_audio_ChipAudio_nativeSmsVgm(JNIEnv* env, jclass, jbyteArray image,
                                                                              jint max_ticks, jbyteArray title,
                                                                              jbyteArray author, jbyteArray date) {
    const std::string t = utf8(env, title), a = utf8(env, author), d = utf8(env, date);
    jbyte* bytes = env->GetByteArrayElements(image, nullptr);
    if (!bytes) return nullptr;  // OutOfMemoryError pending
    size_t size = 0;
    const char* problem = nullptr;
    uint8_t* file = sms_export_vgm(reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(env->GetArrayLength(image)),
                                   static_cast<uint32_t>(max_ticks < 0 ? 0 : max_ticks), t.c_str(), a.c_str(), d.c_str(),
                                   &size, &problem);
    env->ReleaseByteArrayElements(image, bytes, JNI_ABORT);
    if (!file) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), problem ? problem : "Master System export failed");
        return nullptr;
    }
    jbyteArray out = env->NewByteArray(static_cast<jsize>(size));
    if (out) env->SetByteArrayRegion(out, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte*>(file));
    sms_export_free(file);
    return out;
}

}  // extern "C"
