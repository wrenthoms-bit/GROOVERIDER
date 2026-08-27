#include "MicRecorder.h"
#include <android/log.h>
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "grvr.mic", __VA_ARGS__)

namespace grvr {

bool MicRecorder::start(double maxSeconds) {
    std::lock_guard<std::mutex> g(lock_);
    if (stream_) return true;

    oboe::AudioStreamBuilder b;
    b.setDirection(oboe::Direction::Input)
     ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
     ->setSharingMode(oboe::SharingMode::Exclusive)
     ->setFormat(oboe::AudioFormat::Float)
     ->setChannelCount(oboe::ChannelCount::Mono)   // mic is mono; simplest, smallest
     ->setSampleRate(oboe::kUnspecified)
     ->setInputPreset(oboe::InputPreset::Unprocessed) // raw signal, no AGC/NS coloring
     ->setDataCallback(this)
     ->setErrorCallback(this);

    if (b.openStream(stream_) != oboe::Result::OK || !stream_) {
        LOGW("mic openStream failed");
        stream_.reset();
        return false;
    }
    capture_.prepare(stream_->getChannelCount(), stream_->getSampleRate(), maxSeconds);

    if (stream_->requestStart() != oboe::Result::OK) {
        LOGW("mic requestStart failed");
        closeStream();
        return false;
    }
    recording_.store(true, std::memory_order_release);
    return true;
}

void MicRecorder::stop() {
    std::lock_guard<std::mutex> g(lock_);
    recording_.store(false, std::memory_order_release);
    closeStream();
}

void MicRecorder::closeStream() {
    if (!stream_) return;
    stream_->requestStop();
    stream_->close();
    stream_.reset();
}

oboe::DataCallbackResult MicRecorder::onAudioReady(oboe::AudioStream*, void* data, int32_t n) {
    capture_.append(static_cast<const float*>(data), n);
    if (capture_.isFull()) {
        recording_.store(false, std::memory_order_release);
        return oboe::DataCallbackResult::Stop;   // reached the 60 s cap
    }
    return oboe::DataCallbackResult::Continue;
}

void MicRecorder::onErrorAfterClose(oboe::AudioStream*, oboe::Result e) {
    LOGW("mic stream error after close: %d", static_cast<int>(e));
    recording_.store(false, std::memory_order_release);
}

} // namespace grvr
