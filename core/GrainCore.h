#pragma once
#include "gmath.h"
#include <stdint.h>

// ============================================================================
// GrainCore — portable granular engine (spec §2). No STL, no allocation after
// init, no threads, no platform audio API. One render(out, nframes) call is the
// entire hot path. Android (Oboe) and the browser (AudioWorklet+WASM) both just
// feed it a source and pull blocks. This is the single source of truth for the
// sound (per the WASM-port decision).
// ============================================================================

namespace grv {

constexpr int   MAX_GRAINS   = 256;
constexpr int   MAX_FRAMES   = 48000 * 60;   // default source capacity: 60 s @ 48 kHz (spec 2.1)
constexpr int   WIN_N        = 4096;
constexpr int   NUM_WINDOWS  = 3;            // 0=Gaussian 1=Hann 2=Tukey

enum ParamId {
    P_DENSITY = 0,   // grains/s      0.5 .. 200
    P_TIMING_JITTER, // 0 .. 1
    P_GRAIN_MS,      // ms            5 .. 2000
    P_SIZE_JITTER,   // 0 .. 1
    P_POSITION,      // 0 .. 1  (normalised playhead)
    P_SPRAY_MS,      // ms            0 .. 5000
    P_DRIFT,         // -2 .. 2  (source-samples per output-sample)
    P_PITCH,         // semitones    -24 .. 24
    P_PITCH_SPRAY,   // semitones     0 .. 24
    P_REVERSE_PROB,  // 0 .. 1
    P_SPREAD,        // 0 .. 1  (pan width + Haas)
    P_WINDOW,        // 0 .. 2  (index)
    P_OUT_WIDTH,     // 0 .. 2  (mid/side)
    P_OUT_GAIN,      // linear
    P_PLAYING,       // 0/1  gates grain spawning
    P_ANTI_ALIAS,    // 0/1  per-grain lowpass on sped-up reads (spec 2.6); off by default
    P_MAX_VOICES,    // 8 .. MAX_GRAINS  concurrent-grain ceiling (spec 8.4)
    P_COUNT
};

// Stream ids for the hashed RNG (spec 3.2) — independent so tweaking one
// parameter does not re-roll the others.
enum Stream { S_TIMING=1, S_SIZE=2, S_POS=3, S_PITCH=4, S_PAN=5, S_HAAS=6, S_REV=7 };

// --- deterministic hashed RNG: pure function of (seed, stream, index) --------
inline uint64_t splitmix64(uint64_t z){
    z += 0x9E3779B97F4A7C15ull;
    z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ull;
    z = (z ^ (z >> 27)) * 0x94D049BB133111EBull;
    return z ^ (z >> 31);
}
inline float rngf(uint64_t seed, uint32_t stream, uint64_t index){
    uint64_t h = splitmix64(seed ^ ((uint64_t)stream << 48) ^ (index * 0x9E3779B97F4A7C15ull));
    h = splitmix64(h);
    return (float)(uint32_t)(h >> 40) * 0x1.0p-24f;   // [0,1), exact in float32
}

struct Grain {
    double  srcPos;    // fractional read pos in source samples
    double  rate;      // playback ratio (pitch * dir)
    float   haas;      // right-channel source offset in samples
    float   amp;
    float   panL, panR;
    float   aaCoeff, aaL, aaR;   // anti-alias one-pole; aaCoeff == 0 means bypass
    uint32_t age, life;
    uint8_t win;
    bool    active;
};

class GrainCore {
public:
    void init(float sampleRate){
        sr_ = sampleRate;
        buildWindows();
        for (int i=0;i<P_COUNT;i++){ sm_[i]=0; smTarget_[i]=0; }
        // sensible defaults (pad — spec 2.3)
        setParamNow(P_DENSITY, 40); setParamNow(P_TIMING_JITTER,0.15f);
        setParamNow(P_GRAIN_MS,400); setParamNow(P_SIZE_JITTER,0.25f);
        setParamNow(P_POSITION,0.5f); setParamNow(P_SPRAY_MS,250);
        setParamNow(P_DRIFT,0.05f); setParamNow(P_PITCH,0); setParamNow(P_PITCH_SPRAY,0.15f);
        setParamNow(P_REVERSE_PROB,0.2f); setParamNow(P_SPREAD,0.8f);
        setParamNow(P_WINDOW,0); setParamNow(P_OUT_WIDTH,1.0f); setParamNow(P_OUT_GAIN,0.9f);
        setParamNow(P_PLAYING,0);
        setParamNow(P_ANTI_ALIAS,0); setParamNow(P_MAX_VOICES,(float)MAX_GRAINS);
        // smoothing coefficients per class (spec 2.8)
        cGain_ = onepoleSR(0.020f); cGeo_ = onepoleSR(0.080f); cMove_ = onepoleSR(0.050f);
        for (int i=0;i<P_COUNT;i++){
            if (i==P_DENSITY||i==P_GRAIN_MS) smCoeff_[i]=cGeo_;
            else if (i==P_POSITION||i==P_DRIFT||i==P_PITCH) smCoeff_[i]=cMove_;
            else smCoeff_[i]=cGain_;
        }
        rdc_ = 1.f - (TWO_PI*12.f/sr_);
        playhead_ = smTarget_[P_POSITION] * (frames_>0?frames_:1);
        posPrev_ = sm_[P_POSITION];
        gcCur_ = 1.f;
        dcx0L_=dcy0L_=dcx0R_=dcy0R_=0.f;
        activeCount_ = 0; grainIndex_ = 0; nextOnset_ = 0;
    }

    void setSource(int channels, int frames){
        channels_ = channels < 1 ? 1 : (channels > 2 ? 2 : channels);
        frames_   = frames < 0 ? 0 : (frames > capacity_ ? capacity_ : frames);
        playhead_ = sm_[P_POSITION] * (frames_>0?frames_:1);
        posPrev_ = sm_[P_POSITION];
        killAll();
    }

    void setSeed(uint64_t s){ seed_ = s; }
    void setParam(int id, float v){ if(id>=0&&id<P_COUNT) smTarget_[id]=v; }
    void setParamNow(int id, float v){ if(id>=0&&id<P_COUNT){smTarget_[id]=v; sm_[id]=v;} }
    // every param jumps to where it is heading: what a host that sets its
    // params with setParam needs before a render that must not start mid-glide
    void snapParams(){ for (int i=0;i<P_COUNT;i++) sm_[i]=smTarget_[i]; }

    // -------- the entire hot path --------
    void render(float* out, int nframes){
        const bool playing = smTarget_[P_PLAYING] > 0.5f;
        const int maxVoices = clampi((int)(smTarget_[P_MAX_VOICES]+0.5f),8,MAX_GRAINS);
        const float* const bufL = srcL_;
        const float* const bufR = channels_>1 ? srcR_ : srcL_;
        const int frames = frames_;
        for (int n=0;n<nframes;++n){
            advanceSmoothers();
            double density = sm_[P_DENSITY] < 0.5f ? 0.5 : sm_[P_DENSITY];
            const double grainSamp = (double)sm_[P_GRAIN_MS] * 0.001 * sr_;
            // soft-limit density so the cloud never needs more than maxVoices
            // grains: refusing to spawn is silent, stealing one clicks (spec 2.5)
            if (grainSamp > 1.0){
                const double cap = (double)maxVoices * sr_ / grainSamp;
                if (density > cap) density = cap;
            }

            // gain compensation (spec 2.5): 1/(windowRms * sqrt(overlap))
            const int wi = clampi((int)(smTarget_[P_WINDOW]+0.5f),0,NUM_WINDOWS-1);
            const double overlap = density * (double)sm_[P_GRAIN_MS]*0.001;
            float gcTarget = 1.f / (winRms_[wi] * gsqrt((float)(overlap<1?1:overlap)));
            gcCur_ += (gcTarget - gcCur_) * cGain_;

            // schedule (sample-accurate onset clock, spec 2.3)
            if (playing && frames_ > 0){
                nextOnset_ -= 1.0;
                while (nextOnset_ < 0.0){
                    spawn(wi, grainSamp, maxVoices);
                    double interval = sr_ / density;
                    double j = rngf(seed_, S_TIMING, grainIndex_);
                    nextOnset_ += interval * (1.0 + sm_[P_TIMING_JITTER]*(j*2.0-1.0)*0.9);
                }
            }

            // playhead: POSITION moves carry it along, drift scans it. A host
            // that never moves POSITION after setSource gets pure drift.
            const float posNow = sm_[P_POSITION];
            if (posNow != posPrev_){ playhead_ += (double)(posNow - posPrev_) * frames_; posPrev_ = posNow; }
            playhead_ += sm_[P_DRIFT];
            if (frames_>0){
                while (playhead_ >= frames_) playhead_ -= frames_;
                while (playhead_ < 0) playhead_ += frames_;
            }

            // render active grains
            float l=0.f, r=0.f;
            for (int g=0; g<activeCount_; ){
                Grain& gr = pool_[g];
                float w = winSample(gr.win, (float)gr.age / (float)gr.life);
                float sL = readSrc(bufL, frames, gr.srcPos);
                float sR = readSrc(bufR, frames, gr.srcPos + gr.haas);
                if (gr.aaCoeff > 0.f){
                    gr.aaL += (sL - gr.aaL) * gr.aaCoeff; sL = gr.aaL;
                    gr.aaR += (sR - gr.aaR) * gr.aaCoeff; sR = gr.aaR;
                }
                float env = w * gr.amp;
                l += sL * gr.panL * env;
                r += sR * gr.panR * env;
                gr.srcPos += gr.rate;
                if (++gr.age >= gr.life){ pool_[g] = pool_[--activeCount_]; }
                else ++g;
            }
            l *= gcCur_; r *= gcCur_;

            // ---- output stage (spec 2.9) ----
            // DC blocker (~12 Hz one-pole HPF)
            float yL = l - dcx0L_ + rdc_*dcy0L_; dcx0L_=l; dcy0L_=yL;
            float yR = r - dcx0R_ + rdc_*dcy0R_; dcx0R_=r; dcy0R_=yR;
            // width (mid/side)
            float wdt = sm_[P_OUT_WIDTH];
            float mid=(yL+yR)*0.5f, sid=(yL-yR)*0.5f*wdt;
            yL = mid+sid; yR = mid-sid;
            // gentle soft saturation (~-3 dBFS knee) + output gain
            float og = sm_[P_OUT_GAIN];
            out[n*2]   = gtanh(yL*0.7f) * 1.4286f * og;
            out[n*2+1] = gtanh(yR*0.7f) * 1.4286f * og;
        }
    }

    // -------- viz snapshot: writes {posNorm,pitch,amp,ageNorm,pan,active} --------
    int fillCloud(float* dst, int maxN){
        int n = activeCount_ < maxN ? activeCount_ : maxN;
        for (int i=0;i<n;++i){
            Grain& g = pool_[i];
            dst[i*5+0] = frames_>0 ? (float)(g.srcPos / frames_) : 0.f;
            dst[i*5+1] = (float)g.rate;                 // >1 up, <0 reverse
            dst[i*5+2] = g.amp * winSample(g.win,(float)g.age/(float)g.life);
            dst[i*5+3] = (float)g.age/(float)g.life;
            dst[i*5+4] = g.panR;                        // 0..1-ish for hue
        }
        return n;
    }

    int   activeGrains() const { return activeCount_; }
    float sampleRate()   const { return sr_; }
    int   frames()       const { return frames_; }
    double playhead()    const { return playhead_; }          // in source frames
    uint64_t grainIndex() const { return grainIndex_; }       // index the next grain will get

    // read-only introspection for hosts and tests
    const Grain& grainAt(int i) const { return pool_[i]; }
    float windowAt(int wi, float ph) const { return winSample(clampi(wi,0,NUM_WINDOWS-1), ph); }
    float windowRms(int wi) const { return winRms_[clampi(wi,0,NUM_WINDOWS-1)]; }

private:
    static float onepole(float tau){ return 1.f - gexp(-1.f/(tau*48000.f)); } // coeff ref @48k; scaled below
    float onepoleSR(float tau){ return 1.f - gexp(-1.f/(tau*sr_)); }

    void advanceSmoothers(){
        // geometry-class (slow), move-class, gain-class — coefficients set at init
        for (int i=0;i<P_COUNT;i++) sm_[i] += (smTarget_[i]-sm_[i]) * smCoeff_[i];
    }

    void spawn(int wi, double grainSamp, int maxVoices){
        // a refused grain still consumes its index, so the grains after it keep
        // their identity whether or not the pool happened to be full
        uint64_t idx = grainIndex_++;
        if (activeCount_ >= maxVoices) return;
        Grain& g = pool_[activeCount_++];
        g.active = true; g.age = 0; g.win = (uint8_t)wi;

        float sizeJ = 1.f + sm_[P_SIZE_JITTER]*(rngf(seed_,S_SIZE,idx)*2.f-1.f)*0.9f;
        g.life = (uint32_t)(grainSamp * (sizeJ<0.1f?0.1f:sizeJ));
        if (g.life < 4) g.life = 4;

        float sprayS = sm_[P_SPRAY_MS]*0.001f*sr_;
        double pos = playhead_ + (double)(rngf(seed_,S_POS,idx)*2.f-1.f)*sprayS;
        if (frames_>0){ if(pos<0)pos+=frames_; if(pos>=frames_)pos-=frames_; }
        g.srcPos = pos;

        float st = sm_[P_PITCH] + (rngf(seed_,S_PITCH,idx)*2.f-1.f)*sm_[P_PITCH_SPRAY];
        float rate = gexp2(st * (1.f/12.f));
        if (rngf(seed_,S_REV,idx) < sm_[P_REVERSE_PROB]) rate = -rate;
        g.rate = rate;

        // anti-alias one-pole at nyquist/|rate| for sped-up reads (spec 2.6)
        float absRate = gabs(rate);
        g.aaCoeff = (smTarget_[P_ANTI_ALIAS] > 0.5f && absRate > 1.2f) ? 1.f - gexp(-PI/absRate) : 0.f;
        g.aaL = 0.f; g.aaR = 0.f;

        g.haas = (rngf(seed_,S_HAAS,idx)*2.f-1.f) * sm_[P_SPREAD] * 0.010f * sr_;

        float pan = 0.5f + (rngf(seed_,S_PAN,idx)-0.5f)*sm_[P_SPREAD];
        float th = pan * 1.57079633f;          // 0..pi/2
        g.panL = gcos(th); g.panR = gsin(th);
        g.amp = 1.f;
    }

    static inline float readSrc(const float* buf, int frames, double pos){
        float xm1, x0, x1, x2, t;
        if (pos >= 1.0 && pos < (double)(frames-2)){
            // the common case: all four taps inside the source, nothing to wrap or clamp
            const int i1 = (int)pos;
            t = (float)(pos - i1);
            xm1=buf[i1-1]; x0=buf[i1]; x1=buf[i1+1]; x2=buf[i1+2];
        } else {
            if (frames <= 0) return 0.f;
            // wrap
            double p = pos;
            if (p < 0) p += frames * (1 + (int)(-p/frames));
            if (p >= frames) p -= (double)frames * (double)(long long)(p/frames);
            int i1 = (int)p; if (i1 >= frames) i1 = frames-1;
            t = (float)(p - i1);
            int i0 = i1-1<0?0:i1-1, i2=i1+1>=frames?frames-1:i1+1, i3=i1+2>=frames?frames-1:i1+2;
            xm1=buf[i0]; x0=buf[i1]; x1=buf[i2]; x2=buf[i3];
        }
        // Catmull-Rom (spec 2.6)
        float c=(x1-xm1)*0.5f, v=x0-x1, w=c+v, a=w+v+(x2-x0)*0.5f, b=w+a;
        return ((((a*t)-b)*t+c)*t+x0);
    }

    inline float winSample(int wi, float ph) const {
        if (ph<0)ph=0; if(ph>1)ph=1;
        float fx = ph*(WIN_N-1);
        int i=(int)fx; float t=fx-i;
        int j=i+1>=WIN_N?WIN_N-1:i+1;
        const float* w = win_[wi];
        return w[i]+(w[j]-w[i])*t;
    }

    void buildWindows(){
        // 0: Gaussian (pedestal-removed, sigma 0.18) — spec 2.4
        {
            float sigma=0.18f, g0=gexp(-0.5f*(0.5f/sigma)*(0.5f/sigma));
            double sum=0;
            for(int i=0;i<WIN_N;i++){ float x=(float)i/(WIN_N-1);
                float g=gexp(-0.5f*((x-0.5f)/sigma)*((x-0.5f)/sigma));
                float v=(g-g0)/(1.f-g0); win_[0][i]=v; sum+=(double)v*v; }
            winRms_[0]=gsqrt((float)(sum/WIN_N));
        }
        // 1: Hann
        {
            double sum=0;
            for(int i=0;i<WIN_N;i++){ float x=(float)i/(WIN_N-1);
                float v=0.5f-0.5f*gcos(TWO_PI*x); win_[1][i]=v; sum+=(double)v*v; }
            winRms_[1]=gsqrt((float)(sum/WIN_N));
        }
        // 2: Tukey (plateau 0.5)
        {
            float a=0.5f; double sum=0;
            for(int i=0;i<WIN_N;i++){ float x=(float)i/(WIN_N-1); float v;
                if(x<a*0.5f) v=0.5f*(1.f+gcos(PI*(2.f*x/a-1.f)));
                else if(x>1.f-a*0.5f) v=0.5f*(1.f+gcos(PI*(2.f*x/a-2.f/a+1.f)));
                else v=1.f;
                win_[2][i]=v; sum+=(double)v*v; }
            winRms_[2]=gsqrt((float)(sum/WIN_N));
        }
        // force exact zero at both edges (spec 2.4 hard rule)
        for(int w=0;w<NUM_WINDOWS;w++){ win_[w][0]=0.f; win_[w][WIN_N-1]=0.f; }
    }

    void killAll(){ activeCount_=0; }
    static int clampi(int v,int lo,int hi){ return v<lo?lo:(v>hi?hi:v); }

public:
    // host-provided source buffers (live in .bss, not the object) so the WASM
    // binary stays small and the host writes PCM straight in. `capacity` is how
    // many frames each buffer holds; setSource() clamps to it.
    void setBuffers(float* l, float* r, int capacity = MAX_FRAMES){
        srcL_ = l; srcR_ = r; capacity_ = capacity < 0 ? 0 : capacity;
    }
    float* srcL_ = nullptr;
    float* srcR_ = nullptr;

private:
    float sr_ = 48000.f;
    int   channels_ = 1, frames_ = 0, capacity_ = MAX_FRAMES;
    uint64_t seed_ = 0x1234567890ABCDEFull;

    Grain pool_[MAX_GRAINS];
    int   activeCount_ = 0;
    uint64_t grainIndex_ = 0;
    double nextOnset_ = 0;
    double playhead_ = 0;
    float  posPrev_ = 0;

    float sm_[P_COUNT], smTarget_[P_COUNT];
    float cGain_=0.05f, cGeo_=0.02f, cMove_=0.03f;
    float smCoeff_[P_COUNT];
    float rdc_ = 0.998f;
    float gcCur_ = 1.f;
    float dcx0L_=0,dcy0L_=0,dcx0R_=0,dcy0R_=0;

    float win_[NUM_WINDOWS][WIN_N];
    float winRms_[NUM_WINDOWS];
};

} // namespace grv
