// C ABI over GrainCore, shared by the WASM build (browser) and available to the
// Android JNI layer. Single static instance; the host writes PCM straight into
// the exposed source pointers, then drives render().
#include "GrainCore.h"

using namespace grv;

// freestanding: clang may emit calls to these for aggregate init/copies
extern "C" void* memset(void* d, int c, unsigned long n){
    unsigned char* p=(unsigned char*)d; for(unsigned long i=0;i<n;i++)p[i]=(unsigned char)c; return d;
}
extern "C" void* memcpy(void* d, const void* s, unsigned long n){
    unsigned char* a=(unsigned char*)d; const unsigned char* b=(const unsigned char*)s;
    for(unsigned long i=0;i<n;i++)a[i]=b[i]; return d;
}

static GrainCore  g_core;
static float      g_srcL[MAX_FRAMES];   // .bss — not serialized into the wasm binary
static float      g_srcR[MAX_FRAMES];
static float      g_out[4096*2];
static float      g_cloud[MAX_GRAINS*5];

extern "C" {

__attribute__((export_name("grv_init")))
void grv_init(float sampleRate){ g_core.setBuffers(g_srcL, g_srcR); g_core.init(sampleRate); }

__attribute__((export_name("grv_set_source")))
void grv_set_source(int channels, int frames){ g_core.setSource(channels, frames); }

__attribute__((export_name("grv_set_seed")))
void grv_set_seed(unsigned int lo, unsigned int hi){
    g_core.setSeed(((unsigned long long)hi << 32) | (unsigned long long)lo);
}

__attribute__((export_name("grv_set_param")))
void grv_set_param(int id, float v){ g_core.setParam(id, v); }

__attribute__((export_name("grv_set_param_now")))
void grv_set_param_now(int id, float v){ g_core.setParamNow(id, v); }

__attribute__((export_name("grv_render")))
void grv_render(int nframes){
    if (nframes > 4096) nframes = 4096;
    g_core.render(g_out, nframes);
}

__attribute__((export_name("grv_fill_cloud")))
int grv_fill_cloud(int maxN){
    if (maxN > MAX_GRAINS) maxN = MAX_GRAINS;
    return g_core.fillCloud(g_cloud, maxN);
}

__attribute__((export_name("grv_active_grains")))
int grv_active_grains(){ return g_core.activeGrains(); }

// pointer getters (byte offsets into wasm linear memory)
__attribute__((export_name("grv_src_l_ptr"))) float* grv_src_l_ptr(){ return g_srcL; }
__attribute__((export_name("grv_src_r_ptr"))) float* grv_src_r_ptr(){ return g_srcR; }
__attribute__((export_name("grv_out_ptr")))   float* grv_out_ptr(){ return g_out; }
__attribute__((export_name("grv_cloud_ptr"))) float* grv_cloud_ptr(){ return g_cloud; }
__attribute__((export_name("grv_max_frames"))) int   grv_max_frames(){ return MAX_FRAMES; }

}
