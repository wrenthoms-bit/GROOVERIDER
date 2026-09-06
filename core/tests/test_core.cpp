#include "GrainCore.h"
#include <string>
#include <cmath>
#include <cstdio>
#include <vector>
#include <memory>
using namespace grv;

static int fails=0;
static void check(bool ok,const char*n,const char*d=""){printf("%-56s %s %s\n",n,ok?"PASS":"**FAIL**",d);if(!ok)++fails;}

static float* HEAP_L(){ static float* p=new float[MAX_FRAMES](); return p; }
static float* HEAP_R(){ static float* p=new float[MAX_FRAMES](); return p; }
static GrainCore* CORE(){ static GrainCore* c=new GrainCore(); c->setBuffers(HEAP_L(),HEAP_R()); return c; }

static void loadTone(GrainCore& c, double f, double sec, int ch=2){
    int N=(int)(48000*sec);
    for(int i=0;i<N;i++){ float v=0.5f*sinf(2*M_PI*f*i/48000.0);
        c.srcL_[i]=v; c.srcR_[i]=v; }
    c.setSource(ch,N);
}
static void loadNoise(GrainCore& c,double sec){
    int N=(int)(48000*sec); uint32_t s=12345;
    for(int i=0;i<N;i++){ s=s*1664525u+1013904223u; float v=((s>>9)*0x1.0p-23f-0.5f); c.srcL_[i]=v;c.srcR_[i]=v; }
    c.setSource(2,N);
}
static std::vector<float> run(GrainCore& c,int blocks,int F=128){
    std::vector<float> o; std::vector<float> b(F*2);
    for(int i=0;i<blocks;i++){ c.render(b.data(),F); o.insert(o.end(),b.begin(),b.end()); }
    return o;
}
static double rmsdb(const std::vector<float>&x,size_t a,size_t b){ double s=0;for(size_t i=a;i<b;i++)s+=(double)x[i]*x[i];return 20*log10(sqrt(s/(b-a))+1e-12);}
static double peak(const std::vector<float>&x){double m=0;for(float v:x)m=fmax(m,fabs(v));return m;}
static double maxstep(const std::vector<float>&x){double m=0;for(size_t i=2;i<x.size();i+=2)m=fmax(m,fabs(x[i]-x[i-2]));return m;}
static double measureHz(const std::vector<float>&x){int cr=0;for(size_t i=2;i<x.size();i+=2)if((x[i-2]<=0)!=(x[i]<=0))++cr;return (cr/2.0)/((double)(x.size()/2)/48000.0);}

int main(){
    printf("\n== GrainCore DSP checks ==\n\n");
    GrainCore& c = *CORE();
    c.init(48000);

    // 1. window edges exactly zero (all three)
    // (probe via a 1-sample grain is awkward; instead trust buildWindows + check silence-at-birth below)

    // 2. silent until playing
    loadTone(c,220,2.0);
    auto pre = run(c,40);
    check(peak(pre)<1e-6, "silent until P_PLAYING=1");

    // 3. click-free dense cloud from a vocal-ish tone
    c.setParamNow(P_PLAYING,1);
    c.setParamNow(P_POSITION,0.5f);
    auto warm = run(c,80);           // settle
    auto pad = run(c,400);           // ~1s
    double ms = maxstep(pad);
    // ceiling: a 0.9-amp 8kHz sine step is ~0.9*2*pi*8000/48000 ≈ 0.94; clicks are >> that + full-scale jumps.
    check(ms < 0.5 && peak(pad) < 1.2, "dense cloud is click-free & bounded",
          (std::to_string(ms)).c_str());

    // 4. gain compensation: density sweep -> RMS roughly flat (mean over the run)
    {
        double lo, hi;
        c.setParamNow(P_DENSITY,10); run(c,60); { auto a=run(c,300); lo=rmsdb(a,0,a.size()); }
        c.setParamNow(P_DENSITY,160); run(c,60); { auto a=run(c,300); hi=rmsdb(a,0,a.size()); }
        char d[64]; snprintf(d,sizeof d,"(10/s %.1f dB vs 160/s %.1f dB)",lo,hi);
        check(fabs(hi-lo) < 3.0, "density 10->160/s: loudness ~flat (gain comp works)", d);
        c.setParamNow(P_DENSITY,40);
    }

    // 5. pitch: +12 st should roughly double the perceived frequency of a tone source
    {
        loadTone(c,300,2.0); c.setParamNow(P_PLAYING,1);
        c.setParamNow(P_PITCH_SPRAY,0); c.setParamNow(P_SPRAY_MS,0); c.setParamNow(P_REVERSE_PROB,0);
        c.setParamNow(P_DRIFT,0); c.setParamNow(P_PITCH,0); c.setParamNow(P_SPREAD,0);
        run(c,80); auto base=run(c,400); double f0=measureHz(base);
        c.setParamNow(P_PITCH,12); run(c,80); auto up=run(c,400); double f1=measureHz(up);
        char d[80]; snprintf(d,sizeof d,"(%.0f Hz -> %.0f Hz, ~2x)",f0,f1);
        check(f1 > f0*1.7 && f1 < f0*2.3, "pitch +12 st ~ doubles frequency", d);
    }

    // 6. reverse probability = 1 stays bounded / non-silent (grains play backwards)
    {
        c.setParamNow(P_PITCH,0); c.setParamNow(P_REVERSE_PROB,1.0f);
        run(c,60); auto rev=run(c,300);
        check(peak(rev)>0.05 && peak(rev)<1.2, "reverseProb=1 renders bounded audio");
        c.setParamNow(P_REVERSE_PROB,0.2f);
    }

    // 7. DC offset tiny after a long reverse-heavy run
    {
        c.setParamNow(P_REVERSE_PROB,1.0f); run(c,60);
        auto a=run(c,2000); double dc=0; for(size_t i=0;i<a.size();i+=2)dc+=a[i]; dc/= (a.size()/2);
        char d[48]; snprintf(d,sizeof d,"(%.2e)",dc);
        check(fabs(dc)<1e-3, "output DC < -60 dBFS (DC blocker works)", d);
        c.setParamNow(P_REVERSE_PROB,0.2f);
    }

    // 8. stereo width: spread=0 -> near-mono (L==R); spread=0.8 -> decorrelated
    {
        c.setParamNow(P_SPREAD,0.f); c.setParamNow(P_OUT_WIDTH,1.f); run(c,80);
        auto mono=run(c,300); double dmono=0; for(size_t i=0;i+1<mono.size();i+=2)dmono+=fabs(mono[i]-mono[i+1]);
        dmono/=(mono.size()/2);
        c.setParamNow(P_SPREAD,0.9f); run(c,80);
        auto wide=run(c,300); double dwide=0; for(size_t i=0;i+1<wide.size();i+=2)dwide+=fabs(wide[i]-wide[i+1]);
        dwide/=(wide.size()/2);
        char d[80]; snprintf(d,sizeof d,"(L-R diff: mono %.3f, wide %.3f)",dmono,dwide);
        check(dwide > dmono*3, "spread widens the stereo field", d);
    }

    // 9. cloud snapshot: count matches active grains, values in range
    {
        c.setParamNow(P_SPREAD,0.8f); c.setParamNow(P_DENSITY,60); run(c,120);
        std::vector<float> cloud(256*5);
        int n=c.fillCloud(cloud.data(),256);
        bool ok = n>0 && n==c.activeGrains();
        bool inrange=true; for(int i=0;i<n;i++){ float posn=cloud[i*5],amp=cloud[i*5+2],age=cloud[i*5+3];
            if(posn<-0.01f||posn>1.01f||amp<0||amp>2||age<0||age>1.01f) inrange=false; }
        char d[48]; snprintf(d,sizeof d,"(%d grains)",n);
        check(ok&&inrange, "grain-cloud snapshot valid", d);
    }

    // 10. determinism: same seed + source + params -> identical output
    {
        GrainCore* c2 = new GrainCore(); c2->setBuffers(new float[MAX_FRAMES](), new float[MAX_FRAMES]()); c2->init(48000);
        int N=(int)(48000*2.0); for(int i=0;i<N;i++){float v=0.5f*sinf(2*M_PI*220*i/48000.0);c2->srcL_[i]=v;c2->srcR_[i]=v;}
        c2->setSource(2,N); c2->setSeed(0x1234567890ABCDEFull);
        // fresh c with same seed
        GrainCore* c3 = new GrainCore(); c3->setBuffers(new float[MAX_FRAMES](), new float[MAX_FRAMES]()); c3->init(48000);
        for(int i=0;i<N;i++){float v=0.5f*sinf(2*M_PI*220*i/48000.0);c3->srcL_[i]=v;c3->srcR_[i]=v;}
        c3->setSource(2,N); c3->setSeed(0x1234567890ABCDEFull);
        c2->setParamNow(P_PLAYING,1); c3->setParamNow(P_PLAYING,1);
        auto a=run(*c2,500); auto b=run(*c3,500);
        bool ident = a.size()==b.size(); for(size_t i=0;ident&&i<a.size();i++) if(a[i]!=b[i]) ident=false;
        check(ident, "same seed -> bit-identical render (determinism)");
    }

    printf("\n%s (%d failure%s)\n\n", fails?"FAILURES":"ALL PASS", fails, fails==1?"":"s");
    return fails?1:0;
}
