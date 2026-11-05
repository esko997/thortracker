// JNI bridge for org.thortracker.audio.HugeAudio. Every call except nativePosition and
// nativeStats must come from HugeAudio's single control thread.
#include <jni.h>

#include "huge_engine.h"

using thortracker::Engine;

namespace {
Engine* engine(jlong handle) { return reinterpret_cast<Engine*>(handle); }
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_org_thortracker_audio_HugeAudio_nativeCreate(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(Engine::create());
}

JNIEXPORT void JNICALL Java_org_thortracker_audio_HugeAudio_nativeDestroy(JNIEnv*, jclass, jlong h) {
    delete engine(h);
}

JNIEXPORT jint JNICALL Java_org_thortracker_audio_HugeAudio_nativeSetImage(JNIEnv* env, jclass, jlong h,
                                                                          jbyteArray image, jint version) {
    if (env->GetArrayLength(image) != 0x10000) return HM_ERR_IMAGE;
    jbyte* bytes = env->GetByteArrayElements(image, nullptr);
    if (!bytes) return HM_ERR_IMAGE;
    const int result = engine(h)->set_image(reinterpret_cast<const uint8_t*>(bytes), static_cast<uint32_t>(version));
    env->ReleaseByteArrayElements(image, bytes, JNI_ABORT);
    return result;
}

JNIEXPORT jint JNICALL Java_org_thortracker_audio_HugeAudio_nativeEnsureStream(JNIEnv*, jclass, jlong h) {
    return engine(h)->ensure_stream();
}

JNIEXPORT jint JNICALL Java_org_thortracker_audio_HugeAudio_nativePlay(JNIEnv*, jclass, jlong h, jint order,
                                                                      jint row, jint max_ticks) {
    return engine(h)->play(order, row, max_ticks);
}

JNIEXPORT void JNICALL Java_org_thortracker_audio_HugeAudio_nativeStop(JNIEnv*, jclass, jlong h) {
    engine(h)->stop();
}

JNIEXPORT jint JNICALL Java_org_thortracker_audio_HugeAudio_nativeAudition(JNIEnv*, jclass, jlong h, jint channel,
                                                                          jint note, jint instrument, jint detune) {
    return engine(h)->audition(channel, note, instrument, detune);
}

JNIEXPORT void JNICALL Java_org_thortracker_audio_HugeAudio_nativeReleaseAudition(JNIEnv*, jclass, jlong h) {
    engine(h)->release_audition();
}

JNIEXPORT jboolean JNICALL Java_org_thortracker_audio_HugeAudio_nativeSuspendIfIdle(JNIEnv*, jclass, jlong h) {
    return engine(h)->suspend_if_idle() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL Java_org_thortracker_audio_HugeAudio_nativePosition(JNIEnv*, jclass, jlong h) {
    return engine(h)->position();
}

JNIEXPORT void JNICALL Java_org_thortracker_audio_HugeAudio_nativeStats(JNIEnv* env, jclass, jlong h,
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
