// JNI bridge for org.thortracker.audio.ChipAudio. Every call except nativePosition, nativeStats, nativeOpl3Vgm,
// nativeOpmVgm, nativePceVgm, nativeSccVgm, nativeYmExport, nativeSmsVgm, nativeAyVgm and nativeWav* must come from ChipAudio's single
// control thread.
#include <jni.h>

#include <string>

#include "audio_engine.h"
#include "chip_wav.h"
#include "nes/nes_epsm.h"
#include "ay/ay_export.h"
#include "opl3/opl3_export.h"
#include "opm/opm_export.h"
#include "pce/pce_export.h"
#include "scc/scc_export.h"
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

// The EPSM rhythm ROM (nes/nes_epsm.h; any thread, never concurrently with itself). Kotlin: `@JvmStatic external fun
// nativeInstallRhythmRom(rom: ByteArray): Int` — 0 installed (or the same bytes again), -1 wrong size or other bytes
// than the installed ROM. The host checks the ROM's SHA-256 first (EpsmRhythmRom.problem).
JNIEXPORT jint JNICALL Java_org_thortracker_audio_ChipAudio_nativeInstallRhythmRom(JNIEnv* env, jclass, jbyteArray rom) {
    if (!rom) return -1;
    jbyte* bytes = env->GetByteArrayElements(rom, nullptr);
    if (!bytes) return -1;  // OutOfMemoryError pending
    const int result = nes_epsm_install_rhythm_rom(reinterpret_cast<const uint8_t*>(bytes),
                                                   static_cast<size_t>(env->GetArrayLength(rom)));
    env->ReleaseByteArrayElements(rom, bytes, JNI_ABORT);
    return result;
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

// The OPM VGM export (any thread; allocates, renders the whole song: not the audio thread). Returns the file, or
// throws IllegalArgumentException with the loader's reason for a rejected image.
JNIEXPORT jbyteArray JNICALL Java_org_thortracker_audio_ChipAudio_nativeOpmVgm(JNIEnv* env, jclass, jbyteArray image,
                                                                              jint max_ticks, jbyteArray title,
                                                                              jbyteArray author, jbyteArray date) {
    const std::string t = utf8(env, title), a = utf8(env, author), d = utf8(env, date);
    jbyte* bytes = env->GetByteArrayElements(image, nullptr);
    if (!bytes) return nullptr;  // OutOfMemoryError pending
    size_t size = 0;
    const char* problem = nullptr;
    uint8_t* vgm = om_export_vgm(reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(env->GetArrayLength(image)),
                                 static_cast<uint32_t>(max_ticks < 0 ? 0 : max_ticks), t.c_str(), a.c_str(), d.c_str(),
                                 &size, &problem);
    env->ReleaseByteArrayElements(image, bytes, JNI_ABORT);
    if (!vgm) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), problem ? problem : "VGM export failed");
        return nullptr;
    }
    jbyteArray out = env->NewByteArray(static_cast<jsize>(size));
    if (out) env->SetByteArrayRegion(out, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte*>(vgm));
    om_export_free(vgm);
    return out;
}

// The PC Engine VGM export (any thread; allocates, renders the whole song: not the audio thread). Returns the file, or
// throws IllegalArgumentException with the loader's reason for a rejected image.
JNIEXPORT jbyteArray JNICALL Java_org_thortracker_audio_ChipAudio_nativePceVgm(JNIEnv* env, jclass, jbyteArray image,
                                                                              jint max_ticks, jbyteArray title,
                                                                              jbyteArray author, jbyteArray date) {
    const std::string t = utf8(env, title), a = utf8(env, author), d = utf8(env, date);
    jbyte* bytes = env->GetByteArrayElements(image, nullptr);
    if (!bytes) return nullptr;  // OutOfMemoryError pending
    size_t size = 0;
    const char* problem = nullptr;
    uint8_t* vgm = pc_export_vgm(reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(env->GetArrayLength(image)),
                                 static_cast<uint32_t>(max_ticks < 0 ? 0 : max_ticks), t.c_str(), a.c_str(), d.c_str(),
                                 &size, &problem);
    env->ReleaseByteArrayElements(image, bytes, JNI_ABORT);
    if (!vgm) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), problem ? problem : "VGM export failed");
        return nullptr;
    }
    jbyteArray out = env->NewByteArray(static_cast<jsize>(size));
    if (out) env->SetByteArrayRegion(out, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte*>(vgm));
    pc_export_free(vgm);
    return out;
}

// The SCC / SCC+ VGM export (any thread; allocates, renders the whole song: not the audio thread); the image's variant
// decides the chip. Returns the file, or throws IllegalArgumentException with the loader's reason for a rejected image.
JNIEXPORT jbyteArray JNICALL Java_org_thortracker_audio_ChipAudio_nativeSccVgm(JNIEnv* env, jclass, jbyteArray image,
                                                                              jint max_ticks, jbyteArray title,
                                                                              jbyteArray author, jbyteArray date) {
    const std::string t = utf8(env, title), a = utf8(env, author), d = utf8(env, date);
    jbyte* bytes = env->GetByteArrayElements(image, nullptr);
    if (!bytes) return nullptr;  // OutOfMemoryError pending
    size_t size = 0;
    const char* problem = nullptr;
    uint8_t* vgm = sc_export_vgm(reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(env->GetArrayLength(image)),
                                 static_cast<uint32_t>(max_ticks < 0 ? 0 : max_ticks), t.c_str(), a.c_str(), d.c_str(),
                                 &size, &problem);
    env->ReleaseByteArrayElements(image, bytes, JNI_ABORT);
    if (!vgm) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), problem ? problem : "VGM export failed");
        return nullptr;
    }
    jbyteArray out = env->NewByteArray(static_cast<jsize>(size));
    if (out) env->SetByteArrayRegion(out, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte*>(vgm));
    sc_export_free(vgm);
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

// The AY-3-8910 / YM2149 VGM export (any thread; allocates, renders the whole song: not the audio thread): variant 0
// ZX Spectrum 128, 1 MSX, 2 Atari ST, 3 Amstrad CPC (ay_export.h). Kotlin: `@JvmStatic private external fun nativeAyVgm(variant: Int,
// image: ByteArray, maxTicks: Int, title: ByteArray, author: ByteArray, date: ByteArray): ByteArray`. Returns the file,
// or throws IllegalArgumentException with the loader's reason for a rejected image.
JNIEXPORT jbyteArray JNICALL Java_org_thortracker_audio_ChipAudio_nativeAyVgm(JNIEnv* env, jclass, jint variant,
                                                                             jbyteArray image, jint max_ticks,
                                                                             jbyteArray title, jbyteArray author,
                                                                             jbyteArray date) {
    const std::string t = utf8(env, title), a = utf8(env, author), d = utf8(env, date);
    jbyte* bytes = env->GetByteArrayElements(image, nullptr);
    if (!bytes) return nullptr;  // OutOfMemoryError pending
    size_t size = 0;
    const char* problem = nullptr;
    uint8_t* vgm = ay_export_vgm(static_cast<int>(variant), reinterpret_cast<const uint8_t*>(bytes),
                                 static_cast<size_t>(env->GetArrayLength(image)), static_cast<int>(max_ticks), t.c_str(),
                                 a.c_str(), d.c_str(), &size, &problem);
    env->ReleaseByteArrayElements(image, bytes, JNI_ABORT);
    if (!vgm) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), problem ? problem : "AY VGM export failed");
        return nullptr;
    }
    jbyteArray out = env->NewByteArray(static_cast<jsize>(size));
    if (out) env->SetByteArrayRegion(out, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte*>(vgm));
    ay_export_free(vgm);
    return out;
}

// WAV export jobs (chip_wav.h; any thread but the audio thread, one thread per job at a time). Kotlin:
// `nativeWavOpen(system: String, image: ByteArray, loops: Int, fadeMs: Int, maxSeconds: Int, spool: String): Long`
// returns the job, rendering into the file at `spool` (an app-private path), or throws IllegalArgumentException with
// chip_wav_open's problem.
JNIEXPORT jlong JNICALL Java_org_thortracker_audio_ChipAudio_nativeWavOpen(JNIEnv* env, jclass, jstring system,
                                                                          jbyteArray image, jint loops, jint fade_ms,
                                                                          jint max_seconds, jstring spool) {
    const char* id = env->GetStringUTFChars(system, nullptr);
    if (!id) return 0;  // OutOfMemoryError pending
    const char* path = env->GetStringUTFChars(spool, nullptr);
    if (!path) {
        env->ReleaseStringUTFChars(system, id);
        return 0;
    }
    jbyte* bytes = env->GetByteArrayElements(image, nullptr);
    if (!bytes) {
        env->ReleaseStringUTFChars(spool, path);
        env->ReleaseStringUTFChars(system, id);
        return 0;
    }
    const char* problem = nullptr;
    ChipWav* w = chip_wav_open(id, reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(env->GetArrayLength(image)),
                               loops, static_cast<uint32_t>(fade_ms < 0 ? 0 : fade_ms),
                               static_cast<uint32_t>(max_seconds < 0 ? 0 : max_seconds), path, &problem);
    env->ReleaseByteArrayElements(image, bytes, JNI_ABORT);
    env->ReleaseStringUTFChars(spool, path);
    env->ReleaseStringUTFChars(system, id);
    if (!w) env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), problem ? problem : "WAV export failed");
    return reinterpret_cast<jlong>(w);
}

// chip_wav_run: 1 more to do, 0 complete, < 0 failed.
JNIEXPORT jint JNICALL Java_org_thortracker_audio_ChipAudio_nativeWavRun(JNIEnv*, jclass, jlong h, jint ticks) {
    return chip_wav_run(reinterpret_cast<ChipWav*>(h), ticks);
}

// chip_wav_status into out (CHIP_WAV_* order).
JNIEXPORT void JNICALL Java_org_thortracker_audio_ChipAudio_nativeWavStatus(JNIEnv* env, jclass, jlong h, jlongArray out) {
    int64_t values[CHIP_WAV_FIELDS] = {};
    chip_wav_status(reinterpret_cast<ChipWav*>(h), values, CHIP_WAV_FIELDS);
    jlong tmp[CHIP_WAV_FIELDS];
    for (int i = 0; i < CHIP_WAV_FIELDS; ++i) tmp[i] = static_cast<jlong>(values[i]);
    const jsize length = env->GetArrayLength(out);
    env->SetLongArrayRegion(out, 0, length < CHIP_WAV_FIELDS ? length : CHIP_WAV_FIELDS, tmp);
}

// chip_wav_close: frees the job and removes its spool file unless the file is complete.
JNIEXPORT void JNICALL Java_org_thortracker_audio_ChipAudio_nativeWavClose(JNIEnv*, jclass, jlong h) {
    chip_wav_close(reinterpret_cast<ChipWav*>(h));
}

}  // extern "C"
