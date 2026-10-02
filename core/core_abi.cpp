// C ABI over GrainCore and Observatory, exported to WebAssembly for the browser
// build. One engine: the host writes PCM straight into the exposed source
// pointers, sets params, and pulls blocks.
//
//   grv_render      - the grains alone (GrainCore)
//   grv_obs_render  - the whole instrument: grains + chaos + drone + scale-lock + space
#include "Observatory.h"

using namespace grv;

// freestanding: clang may emit calls to these for aggregate init/copies
extern "C" void* memset(void* d, int c, unsigned long n){
    unsigned char* p=(unsigned char*)d; for(unsigned long i=0;i<n;i++)p[i]=(unsigned char)c; return d;
}
extern "C" void* memcpy(void* d, const void* s, unsigned long n){
    unsigned char* a=(unsigned char*)d; const unsigned char* b=(const unsigned char*)s;
    for(unsigned long i=0;i<n;i++)a[i]=b[i]; return d;
}
inline void* operator new(unsigned long, void* place) noexcept { return place; }

// The engines are built in place at grv_init rather than declared as objects:
// an object with initialised members is stored byte for byte in the wasm file,
// and the Observatory's reverb memory alone would add 370 KB of zeros to it.
// Plain storage like this is .bss and costs the file nothing.
alignas(GrainCore)   static unsigned char g_coreMem[sizeof(GrainCore)];
alignas(Observatory) static unsigned char g_obsMem[sizeof(Observatory)];
static GrainCore*   g_core = nullptr;
static Observatory* g_obs  = nullptr;

static float g_srcL[MAX_FRAMES];   // .bss — not serialized into the wasm binary
static float g_srcR[MAX_FRAMES];
static float g_out[4096*2];
static float g_cloud[MAX_GRAINS*5];
static float g_notes[OBS_MAX_NOTES];

extern "C" {

// returns 1 if the Observatory's space runs at this rate (it is sized for 48 kHz and below), else 0
__attribute__((export_name("grv_init")))
int grv_init(float sampleRate){
    g_core = new (g_coreMem) GrainCore();
    g_obs  = new (g_obsMem) Observatory();
    g_core->setBuffers(g_srcL, g_srcR);
    g_core->init(sampleRate);
    return g_obs->init(sampleRate) ? 1 : 0;
}

__attribute__((export_name("grv_set_source")))
void grv_set_source(int channels, int frames){ g_core->setSource(channels, frames); }

__attribute__((export_name("grv_set_seed")))
void grv_set_seed(unsigned int lo, unsigned int hi){
    const unsigned long long seed = ((unsigned long long)hi << 32) | (unsigned long long)lo;
    g_core->setSeed(seed);
    g_obs->setSeed(seed);
}

__attribute__((export_name("grv_set_param")))
void grv_set_param(int id, float v){ g_core->setParam(id, v); }

__attribute__((export_name("grv_set_param_now")))
void grv_set_param_now(int id, float v){ g_core->setParamNow(id, v); }

__attribute__((export_name("grv_render")))
void grv_render(int nframes){
    if (nframes > 4096) nframes = 4096;
    g_core->render(g_out, nframes);
}

__attribute__((export_name("grv_fill_cloud")))
int grv_fill_cloud(int maxN){
    if (maxN > MAX_GRAINS) maxN = MAX_GRAINS;
    return g_core->fillCloud(g_cloud, maxN);
}

__attribute__((export_name("grv_active_grains")))
int grv_active_grains(){ return g_core->activeGrains(); }

// ---- Observatory ----
__attribute__((export_name("grv_obs_param")))
void grv_obs_param(int id, float v){ g_obs->setParam(id, v); }

// call after grv_set_param_now(P_POSITION, pos) + grv_set_source(...)
__attribute__((export_name("grv_obs_source_changed")))
void grv_obs_source_changed(float posNorm){ g_obs->sourceChanged(posNorm); }

// held keyboard notes: write n semitone values at grv_notes_ptr(), then call this
__attribute__((export_name("grv_obs_set_notes")))
void grv_obs_set_notes(int n){ g_obs->setNotes(g_notes, n); }

__attribute__((export_name("grv_obs_render")))
void grv_obs_render(int nframes){
    if (nframes > 4096) nframes = 4096;
    g_obs->render(*g_core, g_out, nframes);
}

__attribute__((export_name("grv_obs_chaos_x"))) float grv_obs_chaos_x(){ return g_obs->chaosX(); }
__attribute__((export_name("grv_obs_chaos_y"))) float grv_obs_chaos_y(){ return g_obs->chaosY(); }
__attribute__((export_name("grv_obs_chaos_z"))) float grv_obs_chaos_z(){ return g_obs->chaosZ(); }
__attribute__((export_name("grv_obs_take_peak"))) float grv_obs_take_peak(){ return g_obs->takePeak(); }
// where the core is reading, in source frames
__attribute__((export_name("grv_playhead"))) double grv_playhead(){ return g_core->playhead(); }

// pointer getters (byte offsets into wasm linear memory)
__attribute__((export_name("grv_src_l_ptr"))) float* grv_src_l_ptr(){ return g_srcL; }
__attribute__((export_name("grv_src_r_ptr"))) float* grv_src_r_ptr(){ return g_srcR; }
__attribute__((export_name("grv_out_ptr")))   float* grv_out_ptr(){ return g_out; }
__attribute__((export_name("grv_cloud_ptr"))) float* grv_cloud_ptr(){ return g_cloud; }
__attribute__((export_name("grv_notes_ptr"))) float* grv_notes_ptr(){ return g_notes; }
__attribute__((export_name("grv_max_frames"))) int   grv_max_frames(){ return MAX_FRAMES; }

}
