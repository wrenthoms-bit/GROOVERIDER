#pragma once
#include <cstdint>
#include <memory>
#include <string>
namespace oboe {
constexpr int32_t kUnspecified = 0;
enum class Direction { Output, Input };
enum class PerformanceMode { None, PowerSaving, LowLatency };
enum class SharingMode { Exclusive, Shared };
enum class AudioFormat { Invalid, Float, I16 };
enum class Usage { Media };
enum class ContentType { Music };
enum class SampleRateConversionQuality { None, Low, Medium, High, Best };
enum class ChannelCount { Mono = 1, Stereo = 2 };
enum class Result { OK, ErrorBase, ErrorDisconnected, ErrorClosed };
enum class AudioApi { Unspecified, OpenSLES, AAudio };
enum class DataCallbackResult { Continue, Stop };
inline const char* convertToText(Result r){ return "Result"; }
inline const char* convertToText(AudioApi a){ return "AudioApi"; }
template <typename T> class ResultWithValue {
public:
    ResultWithValue(T v): value_(v), err_(Result::OK) {}
    explicit operator bool() const { return err_ == Result::OK; }
    T value() const { return value_; }
    Result error() const { return err_; }
private: T value_; Result err_;
};
class AudioStream;
class AudioStreamDataCallback {
public:
    virtual ~AudioStreamDataCallback() = default;
    virtual DataCallbackResult onAudioReady(AudioStream*, void*, int32_t) = 0;
};
class AudioStreamErrorCallback {
public:
    virtual ~AudioStreamErrorCallback() = default;
    virtual void onErrorAfterClose(AudioStream*, Result) {}
    virtual void onErrorBeforeClose(AudioStream*, Result) {}
};
class AudioStream {
public:
    int32_t getSampleRate() const { return 48000; }
    int32_t getFramesPerBurst() const { return 192; }
    int32_t getChannelCount() const { return 2; }
    int32_t getBufferSizeInFrames() const { return 768; }
    int32_t getBufferCapacityInFrames() const { return 3072; }
    SharingMode getSharingMode() const { return SharingMode::Exclusive; }
    PerformanceMode getPerformanceMode() const { return PerformanceMode::LowLatency; }
    AudioApi getAudioApi() const { return AudioApi::AAudio; }
    ResultWithValue<int32_t> setBufferSizeInFrames(int32_t n) { return ResultWithValue<int32_t>(n); }
    ResultWithValue<int32_t> getXRunCount() const { return ResultWithValue<int32_t>(0); }
    ResultWithValue<double> calculateLatencyMillis() { return ResultWithValue<double>(12.0); }
    Result requestStart() { return Result::OK; }
    Result requestStop() { return Result::OK; }
    Result close() { return Result::OK; }
};
class AudioStreamBuilder {
public:
    AudioStreamBuilder* setDirection(Direction){return this;}
    AudioStreamBuilder* setPerformanceMode(PerformanceMode){return this;}
    AudioStreamBuilder* setSharingMode(SharingMode){return this;}
    AudioStreamBuilder* setFormat(AudioFormat){return this;}
    AudioStreamBuilder* setChannelCount(ChannelCount){return this;}
    AudioStreamBuilder* setChannelCount(int){return this;}
    AudioStreamBuilder* setSampleRate(int32_t){return this;}
    AudioStreamBuilder* setSampleRateConversionQuality(SampleRateConversionQuality){return this;}
    AudioStreamBuilder* setUsage(Usage){return this;}
    AudioStreamBuilder* setContentType(ContentType){return this;}
    AudioStreamBuilder* setDataCallback(AudioStreamDataCallback*){return this;}
    AudioStreamBuilder* setErrorCallback(AudioStreamErrorCallback*){return this;}
    Result openStream(std::shared_ptr<AudioStream>& s){ s = std::make_shared<AudioStream>(); return Result::OK; }
};
} // namespace oboe
