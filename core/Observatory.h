#pragma once
#include "GrainCore.h"
#include "Lorenz.h"

// ============================================================================
// Observatory — the layer wrapped around GrainCore that makes the instrument:
// Lorenz chaos swaying position, pitch and width together; the playhead servo
// and DRONE latch; scale-lock; and the space (send high-pass -> allpass
// diffusion -> 8-line FDN -> octave-up shimmer fed back into the send -> tone).
//
// A port of the web app's ObservatoryEngine class (docs/index.html), which is
// the reference for how it should sound. Same curves and constants; float
// maths and hashed randomness instead of doubles and a running PRNG, so it
// sounds like the web version without being bit-identical to it.
//
// Same constraints as GrainCore: no STL, no libm, no allocation, no threads.
// The host owns a GrainCore, feeds it the source and the plain grain params
// (density, grain size, jitters, reverse, window, gain, playing), and calls
// render() here instead of on the core.
// ============================================================================

namespace grv {

constexpr int OBS_MAX_SR    = 48000;   // every buffer below is sized for this
constexpr int OBS_CTL       = 32;      // control tick, in frames (~1.5 kHz @ 48k)
constexpr int OBS_FDN_N     = 8192;    // per-line delay memory (longest line 127 ms + modulation)
constexpr int OBS_AP_MAX    = 752;     // longest diffuser, 15.61 ms
constexpr int OBS_SH_N      = 8192;    // shimmer memory
constexpr int OBS_SH_WIN    = 4320;    // shimmer window, 90 ms
constexpr int OBS_MAX_NOTES = 16;
constexpr int OBS_NUM_SCALES = 7;      // 0 free, chromatic, major, minor, pent-major, pent-minor, fifths

enum ObsParam {
    O_POSITION = 0,  // 0 .. 1     where the playhead is held (the servo's target)
    O_SCAN,          // -1 .. 1    source frames per output frame the target travels; ignored in drone
    O_SPRAY_MS,      // 0 .. 5000  base spray; chaos widens and narrows it
    O_SPREAD,        // 0 .. 1     base pan/Haas spread
    O_WIDTH,         // 0 .. 2     base output width
    O_CHAOS,         // 0 .. 1     Lorenz rate and depth together
    O_PITCH,         // 0 .. 1     pitch amount: scatter range, or spray when the scale is free
    O_KEY,           // 0 .. 11    C .. B
    O_SCALE,         // 0 .. 6     see OBS_NUM_SCALES
    O_REGISTER,      // -24 .. 24  semitones
    O_DETUNE,        // 0 .. 1     semitones of per-grain detune
    O_DRONE,         // 0/1        latch the playhead, slow the chaos, deepen the breath
    O_SPACE,         // 0 .. 1     room (1.2 s) .. ocean (60 s)
    O_SHIMMER,       // 0 .. 1     octave-up feedback
    O_TONE,          // 0 .. 1     low-pass, 500 Hz .. 18 kHz
    O_COUNT
};

// Stream ids for the hashed RNG, continuing GrainCore's list (spec 3.2).
enum ObsStream { S_CHAOS_INIT = 32, S_OBS_NOTE = 33, S_OBS_PITCH_A = 34, S_OBS_PITCH_B = 35 };

class Observatory {
public:
    // false if sampleRate is above OBS_MAX_SR: the chaos and scale-lock still
    // run, but the space is bypassed (the grain signal passes through as is).
    bool init(float sampleRate){
        sr_ = sampleRate;
        fxOk_ = sr_ > 0.f && sr_ <= (float)OBS_MAX_SR;
        static const float defaults[O_COUNT] =
            { 0.5f, 0.f, 250.f, 0.8f, 1.f, 0.1f, 0.15f, 0.f, 0.f, 0.f, 0.05f, 0.f, 0.5f, 0.3f, 0.7f };
        for (int i=0;i<O_COUNT;i++) p_[i] = defaults[i];
        nNotes_ = 0;
        posBase_ = 0; posSeen_ = p_[O_POSITION]; droneSeen_ = false; resync_ = true;
        left_ = 0;
        cx_ = cy_ = cz_ = 0.f; depth_ = 0.f; peak_ = 0.f;
        initFx();
        setSeed(seed_);
        return fxOk_;
    }

    // Seeds the Lorenz start point and the pitch streams. The host gives the
    // same seed to the GrainCore.
    void setSeed(uint64_t seed){
        seed_ = seed;
        x0_ = 0.1f + rngf(seed, S_CHAOS_INIT, 0);
        y0_ = 0.1f + rngf(seed, S_CHAOS_INIT, 1);
        z0_ = 20.f + rngf(seed, S_CHAOS_INIT, 2) * 8.f;
        lorenz_.set(x0_, y0_, z0_);
    }

    void  setParam(int id, float v){ if (id>=0 && id<O_COUNT) p_[id] = clampParam(id, v); }
    float param(int id) const { return (id>=0 && id<O_COUNT) ? p_[id] : 0.f; }

    // Call after core.setParamNow(P_POSITION, posNorm) + core.setSource(...):
    // the servo re-latches onto wherever the core put its playhead.
    void sourceChanged(float posNorm){
        p_[O_POSITION] = clampParam(O_POSITION, posNorm);
        posSeen_ = p_[O_POSITION];
        resync_ = true;
    }

    // Held keyboard notes, in semitones from middle C. Each new grain takes one
    // of them as its pitch centre, so a chord becomes one cloud spread across
    // its notes. None = use key + register.
    void setNotes(const float* semis, int n){
        nNotes_ = n < 0 ? 0 : (n > OBS_MAX_NOTES ? OBS_MAX_NOTES : n);
        for (int i=0;i<nNotes_;i++) notes_[i] = semis[i];
    }

    // -------- the entire hot path: grains + chaos + space --------
    // Control ticks fall every OBS_CTL frames whatever nframes is, so the
    // result does not depend on the host's block size.
    void render(GrainCore& core, float* out, int nframes){
        for (int done=0; done<nframes; ){
            if (left_ <= 0){ control(core); left_ = OBS_CTL; }
            const int m = (nframes-done) < left_ ? (nframes-done) : left_;
            if (fxOk_){
                core.render(blk_, m);
                fx(blk_, out + done*2, m);
            } else {
                core.render(out + done*2, m);
            }
            left_ -= m; done += m;
        }
    }

    // telemetry for visuals
    float chaosX() const { return cx_; }
    float chaosY() const { return cy_; }
    float chaosZ() const { return cz_; }
    float takePeak(){ float v = peak_; peak_ = 0.f; return v; }

private:
    static float clampf(float v, float lo, float hi){ return v<lo?lo:(v>hi?hi:v); }
    static float clampParam(int id, float v){
        switch (id){
            case O_SCAN:     return clampf(v, -1.f, 1.f);
            case O_SPRAY_MS: return clampf(v, 0.f, 5000.f);
            case O_WIDTH:    return clampf(v, 0.f, 2.f);
            case O_KEY:      return clampf(gfloor(v+0.5f), 0.f, 11.f);
            case O_SCALE:    return clampf(gfloor(v+0.5f), 0.f, (float)(OBS_NUM_SCALES-1));
            case O_REGISTER: return clampf(v, -24.f, 24.f);
            case O_DRONE:    return v > 0.5f ? 1.f : 0.f;
            default:         return clampf(v, 0.f, 1.f);
        }
    }

    // 12-bit masks, bit n = semitone n above the key is in the scale; 0 = free
    static int scaleMask(int scale){
        static const int masks[OBS_NUM_SCALES] = {
            0, 0xFFF,
            (1<<0)|(1<<2)|(1<<4)|(1<<5)|(1<<7)|(1<<9)|(1<<11),     // major
            (1<<0)|(1<<2)|(1<<3)|(1<<5)|(1<<7)|(1<<8)|(1<<10),     // minor
            (1<<0)|(1<<2)|(1<<4)|(1<<7)|(1<<9),                    // pentatonic major
            (1<<0)|(1<<3)|(1<<5)|(1<<7)|(1<<10),                   // pentatonic minor
            (1<<0)|(1<<7) };                                       // octaves + fifths
        return masks[scale<0?0:(scale>=OBS_NUM_SCALES?0:scale)];
    }
    static bool inScale(int mask, int semis){ int n = semis % 12; if (n < 0) n += 12; return (mask >> n) & 1; }

    // nearest scale note to `raw`, searching outwards on the side it leans
    static int quant(float raw, int mask){
        const int k = (int)gfloor(raw + 0.5f);
        const bool up = raw >= (float)k;
        for (int d=0; d<7; d++){
            const int a = up ? k+d : k-d, b = up ? k-d : k+d;
            if (inScale(mask, a)) return a;
            if (inScale(mask, b)) return b;
        }
        return k;
    }

    // ---- control tick: chaos -> position / pitch / width together, plus FX coefficients ----
    void control(GrainCore& core){
        const float sr = sr_, dtc = (float)OBS_CTL / sr;
        const bool drone = p_[O_DRONE] > 0.5f;
        const float c = p_[O_CHAOS];

        // Lorenz. Rate in attractor time-units/s: ~0.02 (a loop every ~35 s, lobe
        // changes over minutes) up to ~5 (churn). Drone halves the rate and leans
        // on the depth.
        float rate = 0.02f * gexp(c * LN_250), depth = 0.12f + 0.88f * gpow(c, 0.8f);
        if (drone){ rate *= 0.5f; if (depth < 0.5f) depth = 0.5f; }
        lorenz_.step(rate * dtc);
        if (lorenz_.escaped(100.f, 100.f, 200.f)) lorenz_.set(x0_, y0_, z0_);
        const float xn = clampf(lorenz_.x / 20.f, -1.f, 1.f),
                    yn = clampf(lorenz_.y / 27.f, -1.f, 1.f),
                    zn = clampf((lorenz_.z - 24.f) / 20.f, -1.f, 1.f);
        cx_ = xn; cy_ = yn; cz_ = zn; depth_ = depth;

        // -- grain position: servo the core's playhead (through its DRIFT
        // velocity) onto a chaos-swayed target
        const int frames = core.frames();
        if (frames > 0){
            const double F = (double)frames;
            if (resync_){ posBase_ = core.playhead(); resync_ = false; }
            if (p_[O_POSITION] != posSeen_){ posBase_ = (double)p_[O_POSITION] * F; posSeen_ = p_[O_POSITION]; }
            if (drone && !droneSeen_) posBase_ = core.playhead();        // drone latches where it is
            if (!drone){
                posBase_ += (double)p_[O_SCAN] * OBS_CTL;
                if (posBase_ >= F) posBase_ -= F;
                if (posBase_ < 0) posBase_ += F;
            }
            double range = 0.15 * F; if (range > 3.0 * sr) range = 3.0 * sr;
            if (drone) range *= 0.6;
            double err = posBase_ + (double)(xn * depth) * range - core.playhead();
            err -= F * (double)(long long)(err / F);                      // remainder, sign of err
            if (err > F * 0.5) err -= F; else if (err < -F * 0.5) err += F;
            float vel = (float)(err / (0.12 * sr));
            vel = clampf(vel, -32.f, 32.f);
            core.setParamNow(P_DRIFT, vel);
        }
        droneSeen_ = drone;
        core.setParam(P_SPRAY_MS, p_[O_SPRAY_MS] * (1.f + xn * depth * 0.4f));

        // -- pitch: scale-lock picks a quantised transposition for the next grain;
        // free = breathing spray. Every draw is a pure function of (seed, stream,
        // index of the grain about to spawn).
        const uint64_t gi = core.grainIndex();
        const int key = (int)p_[O_KEY];
        const float reg = p_[O_REGISTER];
        const float transpose = (float)(key > 6 ? key - 12 : key) + reg;
        const int mask = scaleMask((int)p_[O_SCALE]), nn = nNotes_;
        float centre = 0.f;
        if (nn){
            int pick = (int)(rngf(seed_, S_OBS_NOTE, gi) * (float)nn);
            if (pick > nn-1) pick = nn-1;
            centre = notes_[pick] + reg;
        }
        if (mask){
            const float range = p_[O_PITCH] * 24.f * (1.f + yn * depth * 0.5f);
            // triangular: gathers round the root
            const float u = rngf(seed_, S_OBS_PITCH_A, gi) + rngf(seed_, S_OBS_PITCH_B, gi) - 1.f;
            // played notes are absolute (the source is taken to be in C), so the
            // scale stays anchored on the key
            float st = nn ? (float)(key + quant(centre - (float)key + u * range, mask))
                          : transpose + (float)quant(u * range, mask);
            st = clampf(st, -36.f, 36.f);
            core.setParamNow(P_PITCH, st);
            core.setParamNow(P_PITCH_SPRAY, p_[O_DETUNE]);   // unsmoothed: no residual spray leaks out of key
        } else {
            if (nn) core.setParamNow(P_PITCH, centre); else core.setParam(P_PITCH, transpose);
            core.setParam(P_PITCH_SPRAY, p_[O_DETUNE] + p_[O_PITCH] * p_[O_PITCH] * 18.f * (1.f + yn * depth * 0.6f));
        }

        // -- stereo width
        core.setParam(P_SPREAD, clampf(p_[O_SPREAD] * (1.f + zn * depth * 0.35f), 0.f, 1.f));
        core.setParam(P_OUT_WIDTH, clampf(p_[O_WIDTH] * (1.f + zn * depth * 0.3f), 0.f, 2.f));

        if (!fxOk_) return;

        // -- space: decay 1.2 s (room) .. 60 s (ocean), darker as it grows
        const float sp = p_[O_SPACE], rt60 = 1.2f * gexp(sp * LN_50);
        if (rt60 != rt60_){
            rt60_ = rt60; float gm = 0.f;
            for (int i=0;i<8;i++){ g_[i] = gexp(-3.f * LN_10 * (float)len_[i] / (rt60 * sr)); gm += g_[i]; }
            gm *= 0.125f;
            inG_ = 0.62f * gpow(1.f - gm*gm, 0.36f);   // partial energy normalisation: long tails bloom, not blast
        }
        const float tone = p_[O_TONE];
        const float wetT = gpow(sp, 0.8f) * 0.95f, dryT = 1.f - 0.55f * sp;
        const float dampHz = (2200.f + 8500.f * tone) * (1.f - 0.5f * sp);
        dampA_ = 1.f - gexp(-TWO_PI * dampHz / sr);
        float toneHz = 500.f * gexp(tone * LN_36) * gexp2(yn * depth * 0.5f);
        if (toneHz > 0.45f * sr) toneHz = 0.45f * sr;
        toneA_ = 1.f - gexp(-TWO_PI * toneHz / sr);
        shG_ = p_[O_SHIMMER] * 0.62f * (1.f + 0.3f * xn * depth);
        const float breath = gexp(zn * depth * (drone ? 2.2f : 1.0f) * (LN_10 / 20.f));
        // wet, dry and breath glide to their targets across this tick
        wetS_ = (wetT - wet_) * (1.f / OBS_CTL);
        dryS_ = (dryT - dry_) * (1.f / OBS_CTL);
        brS_  = (breath - br_) * (1.f / OBS_CTL);
        for (int i=0;i<8;i++){
            float ph = lfo_[i] + LFO_HZ[i] * dtc; if (ph >= 1.f) ph -= 1.f; lfo_[i] = ph;
            float off = modDepth_ * (1.f + gsin(TWO_PI * ph)); if (off < 0.f) off = 0.f;
            const int oi = (int)off; modI_[i] = oi; modF_[i] = off - (float)oi;
        }
        // states that have decayed to nothing are set to exactly nothing, so no
        // host spends time on denormal arithmetic in a long silence
        hpL_ = flush(hpL_); hpR_ = flush(hpR_); sLpL_ = flush(sLpL_); sLpR_ = flush(sLpR_);
        sHpL_ = flush(sHpL_); sHpR_ = flush(sHpR_); fbL_ = flush(fbL_); fbR_ = flush(fbR_);
        t1L_ = flush(t1L_); t2L_ = flush(t2L_); t1R_ = flush(t1R_); t2R_ = flush(t2R_);
    }

    static float flush(float v){ return (v < 1e-20f && v > -1e-20f) ? 0.f : v; }
    static int roundOdd(float v){ return (int)gfloor(v + 0.5f) | 1; }

    void initFx(){
        static const float lineMs[8] = { 43.7f, 51.1f, 59.3f, 67.9f, 79.3f, 91.7f, 107.3f, 127.1f };
        static const float lfoStart[8] = { 0.f, 0.31f, 0.62f, 0.13f, 0.44f, 0.75f, 0.26f, 0.57f };
        // input diffusion: 4 Schroeder allpasses per channel (left 0-3, right 4-7)
        static const float apMs[8] = { 4.77f, 7.31f, 11.03f, 14.93f, 5.13f, 7.87f, 10.37f, 15.61f };
        const float sr = fxOk_ ? sr_ : (float)OBS_MAX_SR;
        for (int i=0;i<8;i++){
            len_[i] = roundOdd(lineMs[i] * 0.001f * sr);
            g_[i] = 0.f; lp_[i] = 0.f; lfo_[i] = lfoStart[i]; modI_[i] = 0; modF_[i] = 0.f;
            for (int k=0;k<OBS_FDN_N;k++) fd_[i][k] = 0.f;
            apLen_[i] = roundOdd(apMs[i] * 0.001f * sr);
            if (apLen_[i] > OBS_AP_MAX) apLen_[i] = OBS_AP_MAX;
            apI_[i] = 0;
            for (int k=0;k<OBS_AP_MAX;k++) ap_[i][k] = 0.f;
        }
        modDepth_ = 0.0009f * sr; w_ = 0;
        // shimmer: +12 two-tap crossfading shifter in the feedback path
        shWn_ = (int)(sr * 0.09f) & ~1;
        if (shWn_ > OBS_SH_WIN) shWn_ = OBS_SH_WIN;
        for (int i=0;i<OBS_SH_N;i++){ shL_[i] = 0.f; shR_[i] = 0.f; }
        for (int i=0;i<shWn_;i++){ const float s = gsin(PI * (float)i / (float)shWn_); shWin_[i] = s*s; }
        shW_ = 0; shK_ = 0;
        fbL_ = fbR_ = sLpL_ = sLpR_ = sHpL_ = sHpR_ = 0.f;
        hpL_ = hpR_ = t1L_ = t2L_ = t1R_ = t2R_ = 0.f;
        wet_ = 0.f; dry_ = 1.f; br_ = 1.f; wetS_ = dryS_ = brS_ = 0.f;
        rt60_ = -1.f; inG_ = 0.5f; dampA_ = 0.5f; toneA_ = 0.9f; shG_ = 0.f;
        hpA_  = 1.f - gexp(-TWO_PI * 70.f / sr);
        sLpA_ = 1.f - gexp(-TWO_PI * 5200.f / sr);
        sHpA_ = 1.f - gexp(-TWO_PI * 180.f / sr);
    }

    // ---- the space: per sample, over (part of) one control tick ----
    void fx(const float* in, float* out, int m){
        constexpr int M = OBS_FDN_N - 1, SM = OBS_SH_N - 1;
        constexpr float AG = 0.62f;
        const int W = shWn_, HW = W >> 1;
        const float dampA = dampA_, inG = inG_, hpA = hpA_, toneA = toneA_, shG = shG_,
                    sLpA = sLpA_, sHpA = sHpA_;
        int w = w_, sw = shW_, k = shK_;
        float fbL = fbL_, fbR = fbR_, hpL = hpL_, hpR = hpR_, sLpL = sLpL_, sLpR = sLpR_,
              sHpL = sHpL_, sHpR = sHpR_, t1L = t1L_, t2L = t2L_, t1R = t1R_, t2R = t2R_,
              wet = wet_, dry = dry_, br = br_, peak = peak_;
        float tmp[8];
        for (int n=0; n<m; n++){
            const float xl = in[n*2], xr = in[n*2+1];
            // reverb send: low cut, plus the shimmer return (channels crossed for width)
            hpL += (xl - hpL) * hpA; hpR += (xr - hpR) * hpA;
            float il = xl - hpL + fbR * shG + 1e-18f, ir = xr - hpR + fbL * shG + 1e-18f;
            for (int a=0; a<4; a++){
                float* bl = ap_[a]; int ii = apI_[a];
                const float zl = bl[ii], yl = zl - AG * il;
                bl[ii] = il + AG * yl; apI_[a] = ii+1 == apLen_[a] ? 0 : ii+1; il = yl;
                float* bR = ap_[a+4]; int jj = apI_[a+4];
                const float zr = bR[jj], yr = zr - AG * ir;
                bR[jj] = ir + AG * yr; apI_[a+4] = jj+1 == apLen_[a+4] ? 0 : jj+1; ir = yr;
            }
            // 8-line FDN, Householder feedback, damped, slowly modulated
            float s = 0.f;
            for (int i=0; i<8; i++){
                const int rp = w - len_[i] - modI_[i];
                const float a0 = fd_[i][rp & M], a1 = fd_[i][(rp-1) & M];
                lp_[i] += (a0 + (a1 - a0) * modF_[i] - lp_[i]) * dampA;
                const float v = lp_[i] * g_[i];
                tmp[i] = v; s += v;
            }
            s *= 0.25f;
            const float wl = (tmp[0] - tmp[2] + tmp[4] - tmp[6]) * 0.5f,
                        wr = (tmp[1] - tmp[3] + tmp[5] - tmp[7]) * 0.5f;
            il *= inG; ir *= inG;
            fd_[0][w] = tmp[0]-s+il; fd_[1][w] = tmp[1]-s+ir; fd_[2][w] = tmp[2]-s+il; fd_[3][w] = tmp[3]-s+ir;
            fd_[4][w] = tmp[4]-s+il; fd_[5][w] = tmp[5]-s+ir; fd_[6][w] = tmp[6]-s+il; fd_[7][w] = tmp[7]-s+ir;
            w = (w+1) & M;
            // shimmer: octave-up the tail, band-limit, soft-saturate, return it to the send
            shL_[sw] = wl; shR_[sw] = wr;
            const int k2 = k >= HW ? k-HW : k+HW;
            const float ga = shWin_[k], gb = shWin_[k2];
            const float pl = shL_[(sw-(W-k)) & SM] * ga + shL_[(sw-(W-k2)) & SM] * gb;
            const float pr = shR_[(sw-(W-k)) & SM] * ga + shR_[(sw-(W-k2)) & SM] * gb;
            sw = (sw+1) & SM; if (++k == W) k = 0;
            sLpL += (pl - sLpL) * sLpA; sHpL += (sLpL - sHpL) * sHpA; const float fl = sLpL - sHpL;
            sLpR += (pr - sLpR) * sLpA; sHpR += (sLpR - sHpR) * sHpA; const float fr = sLpR - sHpR;
            fbL = fl / (1.f + gabs(fl)); fbR = fr / (1.f + gabs(fr));
            // mix -> tone (12 dB/oct) -> breath -> soft ceiling
            wet += wetS_; dry += dryS_; br += brS_;
            t1L += (xl*dry + wl*wet - t1L) * toneA; t2L += (t1L - t2L) * toneA;
            t1R += (xr*dry + wr*wet - t1R) * toneA; t2R += (t1R - t2R) * toneA;
            float yl = t2L * br * 1.5f, yr = t2R * br * 1.5f;
            float al = gabs(yl), ar = gabs(yr);
            if (al > 0.6f){ al = 0.6f + 0.38f * gtanh((al - 0.6f) / 0.38f); yl = yl < 0.f ? -al : al; }
            else if (al < 1e-20f) yl = 0.f;
            if (ar > 0.6f){ ar = 0.6f + 0.38f * gtanh((ar - 0.6f) / 0.38f); yr = yr < 0.f ? -ar : ar; }
            else if (ar < 1e-20f) yr = 0.f;
            if (al > peak) peak = al;
            if (ar > peak) peak = ar;
            out[n*2] = yl; out[n*2+1] = yr;
        }
        w_ = w; shW_ = sw; shK_ = k; fbL_ = fbL; fbR_ = fbR;
        hpL_ = hpL; hpR_ = hpR; sLpL_ = sLpL; sLpR_ = sLpR; sHpL_ = sHpL; sHpR_ = sHpR;
        t1L_ = t1L; t2L_ = t2L; t1R_ = t1R; t2R_ = t2R;
        wet_ = wet; dry_ = dry; br_ = br; peak_ = peak;
    }

    static constexpr float LN_10 = 2.30258509299f, LN_36 = 3.58351893846f,
                           LN_50 = 3.91202300543f, LN_250 = 5.52146091786f;
    static constexpr float LFO_HZ[8] = { 0.071f, 0.113f, 0.157f, 0.199f, 0.241f, 0.283f, 0.337f, 0.389f };

    float sr_ = 48000.f;
    bool  fxOk_ = true;
    uint64_t seed_ = 0x1234567890ABCDEFull;
    float p_[O_COUNT];
    float notes_[OBS_MAX_NOTES];
    int   nNotes_ = 0;

    // chaos + servo
    Lorenz lorenz_;
    float  x0_ = 1.1f, y0_ = 1.1f, z0_ = 24.f;
    float  cx_ = 0, cy_ = 0, cz_ = 0, depth_ = 0;
    double posBase_ = 0;
    float  posSeen_ = 0.5f;
    bool   droneSeen_ = false, resync_ = true;
    int    left_ = 0;                // frames until the next control tick

    // space
    int   len_[8], modI_[8], apLen_[8], apI_[8];
    float g_[8], lp_[8], lfo_[8], modF_[8];
    float modDepth_ = 0;
    int   w_ = 0, shW_ = 0, shK_ = 0, shWn_ = OBS_SH_WIN;
    float fbL_ = 0, fbR_ = 0, sLpL_ = 0, sLpR_ = 0, sHpL_ = 0, sHpR_ = 0;
    float hpL_ = 0, hpR_ = 0, t1L_ = 0, t2L_ = 0, t1R_ = 0, t2R_ = 0;
    float wet_ = 0, dry_ = 1, br_ = 1, wetS_ = 0, dryS_ = 0, brS_ = 0;
    float rt60_ = -1, inG_ = 0.5f, dampA_ = 0.5f, toneA_ = 0.9f, shG_ = 0;
    float hpA_ = 0, sLpA_ = 0, sHpA_ = 0;
    float peak_ = 0;

    float blk_[OBS_CTL * 2];
    float fd_[8][OBS_FDN_N];
    float ap_[8][OBS_AP_MAX];
    float shL_[OBS_SH_N], shR_[OBS_SH_N];
    float shWin_[OBS_SH_WIN];
};

} // namespace grv
