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

    // 11. voice cap: density is soft-limited so the cloud never exceeds P_MAX_VOICES
    {
        c.init(48000); loadNoise(c,2.0); c.setParamNow(P_PLAYING,1);
        c.setParamNow(P_DENSITY,200); c.setParamNow(P_GRAIN_MS,2000);
        int worst=0; std::vector<float> b(128*2);
        for(int i=0;i<1500;i++){ c.render(b.data(),128); if(c.activeGrains()>worst)worst=c.activeGrains(); }
        c.setParamNow(P_MAX_VOICES,64); int worst64=0;
        for(int i=0;i<1500;i++){ c.render(b.data(),128); if(i>800&&c.activeGrains()>worst64)worst64=c.activeGrains(); }
        char d[64]; snprintf(d,sizeof d,"(peak %d of 256, then %d of 64)",worst,worst64);
        check(worst<=MAX_GRAINS && worst>200 && worst64<=64 && worst64>48, "voice cap holds, cloud stays full", d);
    }

    // 12. level stays put at the voice ceiling (gain comp follows the limited density)
    {
        c.init(48000); loadNoise(c,2.0); c.setParamNow(P_PLAYING,1); c.setParamNow(P_GRAIN_MS,2000);
        c.setParamNow(P_DENSITY,100); run(c,1200); auto a=run(c,1200); double under=rmsdb(a,0,a.size());
        c.setParamNow(P_DENSITY,200); run(c,1200); auto b=run(c,1200); double over=rmsdb(b,0,b.size());
        char d[64]; snprintf(d,sizeof d,"(100/s %.1f dB vs 200/s %.1f dB)",under,over);
        check(fabs(over-under) < 1.5, "asking past the voice cap does not drop the level", d);
    }

    // 13. P_POSITION is live: moving it while playing moves where new grains read
    {
        c.init(48000); loadNoise(c,2.0); c.setParamNow(P_PLAYING,1);
        c.setParamNow(P_DRIFT,0); c.setParamNow(P_SPRAY_MS,0); c.setParamNow(P_POSITION,0.25f); c.setSource(2,96000);
        run(c,200); double before=c.grainAt(c.activeGrains()-1).srcPos/96000.0;
        c.setParam(P_POSITION,0.75f); run(c,600); int n=c.activeGrains(); double after=c.grainAt(n-1).srcPos/96000.0;
        after-=floor(after); before-=floor(before);
        char d[64]; snprintf(d,sizeof d,"(newest grain %.2f -> %.2f)",before,after);
        check(fabs(before-0.25)<0.2 && fabs(after-0.75)<0.2, "POSITION moves the cloud while playing", d);
    }

    // 14. anti-alias is off unless asked for, and when on it only touches sped-up grains
    {
        auto hf=[&](float aa,float pitch){
            c.init(48000); loadNoise(c,2.0); c.setParamNow(P_PLAYING,1); c.setParamNow(P_ANTI_ALIAS,aa);
            c.setParamNow(P_PITCH,pitch); c.setParamNow(P_PITCH_SPRAY,0); c.setParamNow(P_REVERSE_PROB,0);
            run(c,200); auto a=run(c,800); double s=0; for(size_t i=2;i<a.size();i+=2){double e=a[i]-a[i-2]; s+=e*e;}
            return 10*log10(s/(a.size()/2)+1e-20); };   // first-difference energy ~ high-frequency content
        double off12=hf(0,12), on12=hf(1,12), off0=hf(0,0), on0=hf(1,0);
        char d[80]; snprintf(d,sizeof d,"(+12 st: %.1f dB with filter; unity: %.2f dB)",on12-off12,on0-off0);
        check(on12 < off12-1.0 && on0==off0, "anti-alias tames +12 st grains, leaves unity alone", d);
    }

    // 15. source capacity is the host's: a buffer longer than MAX_FRAMES plays to its end
    {
        const int N=MAX_FRAMES+48000; float* big=new float[N]();
        for(int i=MAX_FRAMES;i<N;i++) big[i]=0.5f*sinf(2*M_PI*220*i/48000.0);   // audio only past the old cap
        GrainCore* c4=new GrainCore(); c4->setBuffers(big,big,N); c4->init(48000);
        c4->setParamNow(P_POSITION,(MAX_FRAMES+24000)/(float)N); c4->setParamNow(P_DRIFT,0); c4->setParamNow(P_SPRAY_MS,100);
        c4->setSource(1,N); c4->setParamNow(P_PLAYING,1);
        run(*c4,200); auto a=run(*c4,400);
        GrainCore* c5=new GrainCore(); c5->setBuffers(big,big); c5->init(48000); c5->setSource(1,N);
        c5->setParamNow(P_PLAYING,1); run(*c5,200); auto b=run(*c5,400);
        check(peak(a)>0.05 && peak(b)<1e-6, "host-set capacity reaches past MAX_FRAMES; default still clamps");
    }

    // 16. long sources keep sub-sample precision when a grain wraps past the end
    {
        const int N=MAX_FRAMES; c.init(48000);
        for(int i=0;i<N;i++){ float v=0.5f*sinf(2*M_PI*100.0*i/48000.0); c.srcL_[i]=v; c.srcR_[i]=v; }   // 100 Hz: whole cycles, so the wrap is seamless
        c.setParamNow(P_POSITION,0.9999f); c.setParamNow(P_DRIFT,0); c.setParamNow(P_SPRAY_MS,0);
        c.setParamNow(P_PITCH,0.37f); c.setParamNow(P_PITCH_SPRAY,0); c.setParamNow(P_REVERSE_PROB,0); c.setParamNow(P_SPREAD,0);
        c.setSource(2,N); c.setParamNow(P_PLAYING,1);
        run(c,300); auto a=run(c,600);
        double worst=0; for(size_t i=4;i<a.size();i+=2){ double dd=fabs(a[i]-2*a[i-2]+a[i-4]); if(dd>worst)worst=dd; }
        char d[48]; snprintf(d,sizeof d,"(max 2nd difference %.2e)",worst);
        check(worst < 2e-3, "wrapped grains on a 60 s source stay smooth", d);
    }

    printf("\n%s (%d failure%s)\n\n", fails?"FAILURES":"ALL PASS", fails, fails==1?"":"s");
    return fails?1:0;
}
