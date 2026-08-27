#pragma once
#include <oboe/Oboe.h>
#include <atomic>
#include <memory>
#include <mutex>
#include "CaptureBuffer.h"

namespace grvr {

/// Records the microphone to a CaptureBuffer via an Oboe input stream.
/// Independent of the output Engine: its own stream, its own callback. Capped
/// at 60 s (spec 1.1 / M1). No processing here -- just capture and metering.
class MicRecorder : public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback {
public:
    ~MicRecorder() override { stop(); }

    bool start(double maxSeconds = 60.0);
    void stop();
    bool isRecording() const noexcept { return recording_.load(std::memory_order_acquire); }

    float   level()          noexcept { return capture_.takePeak(); }
    int64_t capturedFrames() const noexcept { return capture_.capturedFrames(); }
    int32_t sampleRate()     const noexcept { return capture_.sampleRate(); }
    int32_t channels()       const noexcept { return capture_.channels(); }
    bool    reachedCap()     const noexcept { return capture_.isFull(); }

    /// After stop(): deinterleave the take for handing to a SourceBuffer.
    std::vector<std::vector<float>> extractChannels() const { return capture_.extractChannels(); }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* s, void* data, int32_t n) override;
    void onErrorAfterClose(oboe::AudioStream* s, oboe::Result e) override;

private:
    void closeStream();

    std::mutex                         lock_;
    std::shared_ptr<oboe::AudioStream> stream_;
    CaptureBuffer                      capture_;
    std::atomic<bool>                  recording_ {false};
};

} // namespace grvr
