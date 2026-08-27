#include <jni.h>
#include <memory>
#include <mutex>
#include <vector>

#include "../engine/Engine.h"
#include "../engine/OfflineRenderer.h"
#include "../dsp/Resampler.h"
#include "../io/SourceBuffer.h"
#include "../io/MicRecorder.h"

namespace {

std::unique_ptr<grvr::Engine>      gEngine;
std::unique_ptr<grvr::MicRecorder> gRecorder;
std::mutex                         gLock;

grvr::Engine* engineOrNull() {
    std::lock_guard<std::mutex> lock(gLock);
    return gEngine.get();
}
grvr::MicRecorder* recorderOrNull() {
    std::lock_guard<std::mutex> lock(gLock);
    return gRecorder.get();
}

} // namespace

extern "C" {

// ===================================================================== engine

JNIEXPORT void JNICALL
Java_com_delrogue_grooverider_engine_GrooveriderEngine_nativeCreate(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gLock);
    if (!gEngine)   gEngine   = std::make_unique<grvr::Engine>();
    if (!gRecorder) gRecorder = std::make_unique<grvr::MicRecorder>();
}

JNIEXPORT void JNICALL
Java_com_delrogue_grooverider_engine_GrooveriderEngine_nativeDestroy(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gLock);
    gRecorder.reset();
    gEngine.reset();
}

JNIEXPORT jboolean JNICALL
Java_com_delrogue_grooverider_engine_GrooveriderEngine_nativeStart(JNIEnv*, jobject) {
    auto* e = engineOrNull();
    return (e && e->start()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_delrogue_grooverider_engine_GrooveriderEngine_nativeStop(JNIEnv*, jobject) {
    if (auto* e = engineOrNull()) e->stop();
}

JNIEXPORT jboolean JNICALL
Java_com_delrogue_grooverider_engine_GrooveriderEngine_nativeIsRunning(JNIEnv*, jobject) {
    auto* e = engineOrNull();
    return (e && e->isRunning()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_delrogue_grooverider_engine_GrooveriderEngine_nativeSetParam(JNIEnv*, jobject,
                                                                     jint id, jfloat value) {
    if (auto* e = engineOrNull())
        e->setParam(static_cast<uint16_t>(id), static_cast<float>(value));
}

JNIEXPORT void JNICALL
Java_com_delrogue_grooverider_engine_GrooveriderEngine_nativeSetMasterSeed(JNIEnv*, jobject,
                                                                          jlong seed) {
    if (auto* e = engineOrNull()) e->setMasterSeed(static_cast<int64_t>(seed));
}

JNIEXPORT void JNICALL
Java_com_delrogue_grooverider_engine_GrooveriderEngine_nativeSetChaosEnabled(JNIEnv*, jobject,
                                                                            jboolean on) {
    if (auto* e = engineOrNull()) e->setChaosEnabled(on == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_delrogue_grooverider_engine_GrooveriderEngine_nativePollMeters(JNIEnv* env, jobject,
                                                                       jfloatArray out) {
    if (out == nullptr || env->GetArrayLength(out) < 9) return;
    grvr::Meters m;
    if (auto* e = engineOrNull()) e->pollMeters(m);
    jfloat v[9] = {
        m.peakL, m.peakR, m.latencyMs, m.cpuLoad,
        static_cast<jfloat>(m.xruns), static_cast<jfloat>(m.bufferFrames),
        static_cast<jfloat>(m.bufferGrows), static_cast<jfloat>(m.running),
        static_cast<jfloat>(m.activeVoices)
    };
    env->SetFloatArrayRegion(out, 0, 9, v);
}

/// Flat layout: [0] = count, then 5 floats per grain (sourcePosNorm,
/// pitchRatio, amp, age01, pan), up to kMaxGrains. Caller preallocates so
/// polling at 60 Hz does not allocate.
JNIEXPORT void JNICALL
Java_com_delrogue_grooverider_engine_GrooveriderEngine_nativePollCloud(JNIEnv* env, jobject,
                                                                       jfloatArray out) {
    constexpr int32_t kStride = 5;
    const jsize needed = 1 + grvr::kMaxGrains * kStride;
    if (out == nullptr || env->GetArrayLength(out) < needed) return;

    grvr::GrainCloudSnapshot snap;
    if (auto* e = engineOrNull()) e->pollCloud(snap);

    std::vector<jfloat> flat(static_cast<size_t>(needed), 0.0f);
    flat[0] = static_cast<jfloat>(snap.count);
    for (int32_t i = 0; i < snap.count; ++i) {
        const grvr::GrainVisual& v = snap.grains[i];
        const size_t base = 1 + static_cast<size_t>(i) * kStride;
        flat[base + 0] = v.sourcePosNorm;
        flat[base + 1] = v.pitchRatio;
        flat[base + 2] = v.amp;
        flat[base + 3] = v.age01;
        flat[base + 4] = v.pan;
    }
    env->SetFloatArrayRegion(out, 0, needed, flat.data());
}

JNIEXPORT jshortArray JNICALL
Java_com_delrogue_grooverider_engine_GrooveriderEngine_nativeCaptureSnapshot(JNIEnv* env, jobject,
                                                                             jdouble seconds) {
    auto* e = engineOrNull();
    std::vector<int16_t> pcm = e ? e->captureSnapshot(static_cast<double>(seconds)) : std::vector<int16_t>{};
    jshortArray result = env->NewShortArray(static_cast<jsize>(pcm.size()));
    if (result && !pcm.empty()) {
        env->SetShortArrayRegion(result, 0, static_cast<jsize>(pcm.size()), pcm.data());
    }
    return result;
}

JNIEXPORT jint JNICALL
Java_com_delrogue_grooverider_engine_GrooveriderEngine_nativeCaptureSampleRate(JNIEnv*, jobject) {
    auto* e = engineOrNull();
    return e ? e->captureSampleRate() : 48000;
}

// --------------------------------------------------------------- offline render (M6)

/// `params` (14 floats, in this order): density, timingJitter, grainSizeMs,
/// sizeJitter, position, sprayMs, drift, pitchSt, pitchSpraySt, reverseProb,
/// spread, outputWidth, outputGain, chaosRate. Keep in sync with
/// GrooveriderEngine.offlineRender's Kotlin-side packing.
JNIEXPORT jfloatArray JNICALL
Java_com_delrogue_grooverider_engine_GrooveriderEngine_nativeOfflineRender(
        JNIEnv* env, jobject,
        jfloatArray pcm, jint channels, jint srcRate, jint dstRate,
        jfloatArray params, jint windowType, jboolean chaosEnabled,
        jlong masterSeed, jdouble durationSeconds, jboolean seamlessLoop, jdouble crossfadeSeconds) {
    if (pcm == nullptr || channels < 1) return env->NewFloatArray(0);

    const jsize total = env->GetArrayLength(pcm);
    const int64_t frames = total / channels;
    std::vector<jfloat> interleaved(static_cast<size_t>(total));
    env->GetFloatArrayRegion(pcm, 0, total, interleaved.data());

    std::vector<std::vector<float>> chans(static_cast<size_t>(channels));
    for (int c = 0; c < channels; ++c) chans[c].resize(static_cast<size_t>(frames));
    for (int64_t i = 0; i < frames; ++i)
        for (int c = 0; c < channels; ++c)
            chans[static_cast<size_t>(c)][static_cast<size_t>(i)] =
                interleaved[static_cast<size_t>(i * channels + c)];
    const grvr::SourceBuffer source(std::move(chans), srcRate);

    jfloat p[14] = {};
    env->GetFloatArrayRegion(params, 0, 14, p);

    grvr::RenderRequest req;
    req.masterSeed = static_cast<uint64_t>(masterSeed);
    req.density = p[0]; req.timingJitter = p[1]; req.grainSizeMs = p[2]; req.sizeJitter = p[3];
    req.position = p[4]; req.sprayMs = p[5]; req.drift = p[6]; req.pitchSt = p[7];
    req.pitchSpraySt = p[8]; req.reverseProb = p[9]; req.spread = p[10];
    req.outputWidth = p[11]; req.outputGain = p[12]; req.chaosRate = p[13];
    req.windowType = static_cast<uint16_t>(windowType);
    req.chaosEnabled = (chaosEnabled == JNI_TRUE);
    req.durationSeconds = static_cast<double>(durationSeconds);
    req.seamlessLoop = (seamlessLoop == JNI_TRUE);
    req.crossfadeSeconds = static_cast<double>(crossfadeSeconds);

    const std::vector<float> out = grvr::OfflineRenderer::render(source, req, dstRate);
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(out.size()));
    if (result && !out.empty()) {
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(out.size()), out.data());
    }
    return result;
}

JNIEXPORT jint JNICALL
Java_com_delrogue_grooverider_engine_GrooveriderEngine_nativeProfileAndSetVoiceCap(JNIEnv*, jobject) {
    auto* e = engineOrNull();
    return e ? e->profileAndSetVoiceCap() : 64;
}

JNIEXPORT jstring JNICALL
Java_com_delrogue_grooverider_engine_GrooveriderEngine_nativeConfigDescription(JNIEnv* env, jobject) {
    auto* e = engineOrNull();
    const std::string d = e ? e->configDescription() : std::string("engine not created");
    return env->NewStringUTF(d.c_str());
}

JNIEXPORT jint JNICALL
Java_com_delrogue_grooverider_engine_GrooveriderEngine_nativeEngineSampleRate(JNIEnv*, jobject) {
    auto* e = engineOrNull();
    return e ? static_cast<jint>(e->engineSampleRate()) : 0;
}

// ===================================================================== source

/// Deinterleave, resample to the engine rate, hand to the engine for preview.
/// `pcm` is interleaved float32 at `srcRate`. Returns resampled frame count.
JNIEXPORT jlong JNICALL
Java_com_delrogue_grooverider_engine_GrooveriderEngine_nativeLoadSource(
        JNIEnv* env, jobject, jfloatArray pcm, jint channels, jint srcRate, jint dstRate) {
    auto* e = engineOrNull();
    if (!e || pcm == nullptr || channels < 1) return 0;

    const jsize total = env->GetArrayLength(pcm);
    const int64_t frames = total / channels;
    if (frames <= 0) return 0;

    std::vector<std::vector<float>> chans(static_cast<size_t>(channels));
    for (int c = 0; c < channels; ++c) chans[c].resize(static_cast<size_t>(frames));

    // Copy + deinterleave in one pass (region copy, no pin/copyback surprises).
    std::vector<jfloat> tmp(static_cast<size_t>(total));
    env->GetFloatArrayRegion(pcm, 0, total, tmp.data());
    for (int64_t i = 0; i < frames; ++i)
        for (int c = 0; c < channels; ++c)
            chans[c][static_cast<size_t>(i)] = tmp[static_cast<size_t>(i * channels + c)];

    grvr::SourceBuffer raw(std::move(chans), srcRate);
    const int32_t target = dstRate > 0 ? dstRate : srcRate;
    auto resampled = std::make_shared<grvr::SourceBuffer>(
        grvr::Resampler::resample(raw, target));
    const int64_t outFrames = resampled->frames();
    e->setSource(std::move(resampled));
    return static_cast<jlong>(outFrames);
}

JNIEXPORT void JNICALL
Java_com_delrogue_grooverider_engine_GrooveriderEngine_nativeClearSource(JNIEnv*, jobject) {
    if (auto* e = engineOrNull()) e->clearSource();
}

JNIEXPORT void JNICALL
Java_com_delrogue_grooverider_engine_GrooveriderEngine_nativePreviewPlay(JNIEnv*, jobject, jboolean on) {
    if (auto* e = engineOrNull()) e->previewPlay(on == JNI_TRUE);
}
JNIEXPORT void JNICALL
Java_com_delrogue_grooverider_engine_GrooveriderEngine_nativePreviewSeek(JNIEnv*, jobject, jlong f) {
    if (auto* e = engineOrNull()) e->previewSeek(static_cast<int64_t>(f));
}
JNIEXPORT void JNICALL
Java_com_delrogue_grooverider_engine_GrooveriderEngine_nativePreviewRegion(JNIEnv*, jobject,
                                                                          jlong in, jlong out) {
    if (auto* e = engineOrNull()) e->previewSetRegion(static_cast<int64_t>(in), static_cast<int64_t>(out));
}
JNIEXPORT void JNICALL
Java_com_delrogue_grooverider_engine_GrooveriderEngine_nativePreviewLoop(JNIEnv*, jobject, jboolean on) {
    if (auto* e = engineOrNull()) e->previewSetLoop(on == JNI_TRUE);
}
JNIEXPORT void JNICALL
Java_com_delrogue_grooverider_engine_GrooveriderEngine_nativePreviewGain(JNIEnv*, jobject, jfloat g) {
    if (auto* e = engineOrNull()) e->previewSetGain(static_cast<float>(g));
}
JNIEXPORT jlong JNICALL
Java_com_delrogue_grooverider_engine_GrooveriderEngine_nativePreviewPosition(JNIEnv*, jobject) {
    auto* e = engineOrNull();
    return e ? static_cast<jlong>(e->previewPosition()) : 0;
}
JNIEXPORT jboolean JNICALL
Java_com_delrogue_grooverider_engine_GrooveriderEngine_nativePreviewIsPlaying(JNIEnv*, jobject) {
    auto* e = engineOrNull();
    return (e && e->previewIsPlaying()) ? JNI_TRUE : JNI_FALSE;
}

// ===================================================================== mic

JNIEXPORT jboolean JNICALL
Java_com_delrogue_grooverider_engine_GrooveriderEngine_nativeMicStart(JNIEnv*, jobject, jdouble maxSec) {
    auto* r = recorderOrNull();
    return (r && r->start(static_cast<double>(maxSec))) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT void JNICALL
Java_com_delrogue_grooverider_engine_GrooveriderEngine_nativeMicStop(JNIEnv*, jobject) {
    if (auto* r = recorderOrNull()) r->stop();
}
JNIEXPORT jboolean JNICALL
Java_com_delrogue_grooverider_engine_GrooveriderEngine_nativeMicIsRecording(JNIEnv*, jobject) {
    auto* r = recorderOrNull();
    return (r && r->isRecording()) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jfloat JNICALL
Java_com_delrogue_grooverider_engine_GrooveriderEngine_nativeMicLevel(JNIEnv*, jobject) {
    auto* r = recorderOrNull();
    return r ? static_cast<jfloat>(r->level()) : 0.0f;
}
JNIEXPORT jlong JNICALL
Java_com_delrogue_grooverider_engine_GrooveriderEngine_nativeMicFrames(JNIEnv*, jobject) {
    auto* r = recorderOrNull();
    return r ? static_cast<jlong>(r->capturedFrames()) : 0;
}
JNIEXPORT jint JNICALL
Java_com_delrogue_grooverider_engine_GrooveriderEngine_nativeMicSampleRate(JNIEnv*, jobject) {
    auto* r = recorderOrNull();
    return r ? static_cast<jint>(r->sampleRate()) : 0;
}
JNIEXPORT jint JNICALL
Java_com_delrogue_grooverider_engine_GrooveriderEngine_nativeMicChannels(JNIEnv*, jobject) {
    auto* r = recorderOrNull();
    return r ? static_cast<jint>(r->channels()) : 0;
}

/// Pull the captured take out as interleaved float32 at the capture rate, for
/// hashing + storage on the Kotlin side. Returns an empty array if nothing.
JNIEXPORT jfloatArray JNICALL
Java_com_delrogue_grooverider_engine_GrooveriderEngine_nativeMicExtract(JNIEnv* env, jobject) {
    auto* r = recorderOrNull();
    if (!r) return env->NewFloatArray(0);
    auto chans = r->extractChannels();
    const int channels = static_cast<int>(chans.size());
    const int64_t frames = channels ? static_cast<int64_t>(chans[0].size()) : 0;
    jfloatArray out = env->NewFloatArray(static_cast<jsize>(frames * channels));
    if (frames == 0 || out == nullptr) return out;
    std::vector<jfloat> inter(static_cast<size_t>(frames * channels));
    for (int64_t i = 0; i < frames; ++i)
        for (int c = 0; c < channels; ++c)
            inter[static_cast<size_t>(i * channels + c)] = chans[c][static_cast<size_t>(i)];
    env->SetFloatArrayRegion(out, 0, static_cast<jsize>(frames * channels), inter.data());
    return out;
}

} // extern "C"
