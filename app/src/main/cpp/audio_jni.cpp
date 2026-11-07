// JNI bridge for org.thortracker.audio.ChipAudio. Every call except nativePosition and
// nativeStats must come from ChipAudio's single control thread.
#include <jni.h>

#include "audio_engine.h"

using thortracker::Engine;

namespace {
Engine* engine(jlong handle) { return reinterpret_cast<Engine*>(handle); }
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

}  // extern "C"
