// Engine checks for the web app's ObservatoryEngine (docs/index.html), run in Node against the
// real core WASM. No browser needed:  node engine.test.mjs
// The class is lifted out of the page between its ENGINE-BEGIN / ENGINE-END markers, so this
// always tests exactly what ships. It is also the reference the Android port is matched against.
import fs from 'fs';
const root = new URL('..', import.meta.url);
const html = fs.readFileSync(new URL('docs/index.html', root), 'utf8');
const src = html.split('/*ENGINE-BEGIN*/')[1].split('/*ENGINE-END*/')[0];
const Engine = new Function(src + '; return ObservatoryEngine;')();
const wasm = fs.readFileSync(new URL('core/grooverider.wasm', root));
const mod = new WebAssembly.Module(wasm);
const sr = 48000;
const mk = () => new Engine(new WebAssembly.Instance(mod,{}), sr);
function demo(f0=130.81, dur=6){ const N=sr*dur, l=new Float32Array(N), r=new Float32Array(N);
  for(let h=1;h<=6;h++){ const f=Math.round(f0*h*dur)/dur; for(let i=0;i<N;i++){ const v=0.25/h*Math.sin(2*Math.PI*f*i/sr+h); l[i]+=v; r[i]+=v; } } return {l,r,N}; }
function sine(f, dur=4){ const N=sr*dur, l=new Float32Array(N); for(let i=0;i<N;i++) l[i]=0.4*Math.sin(2*Math.PI*f*i/sr); return {l,r:l,N}; }
function run(eng, secs, onBlock){ const n=Math.round(secs*sr/128), L=new Float32Array(n*128), R=new Float32Array(n*128), a=new Float32Array(128), b=new Float32Array(128);
  for(let i=0;i<n;i++){ eng.process(a,b,128); L.set(a,i*128); R.set(b,i*128); onBlock&&onBlock(i,eng); } return {L,R}; }
const rms = (a,s=0,e=a.length)=>{ let t=0; for(let i=s;i<e;i++) t+=a[i]*a[i]; return Math.sqrt(t/(e-s)); };
const db = v => 20*Math.log10(v+1e-12);
const peak = a => { let m=0; for(const v of a){ const x=Math.abs(v); if(x>m)m=x; } return m; };
const bad = a => { for(const v of a) if(!Number.isFinite(v)) return true; return false; };
function goertzel(a, f, s, e){ const w=2*Math.PI*f/sr, c=2*Math.cos(w); let s1=0,s2=0; for(let i=s;i<e;i++){ const s0=a[i]+c*s1-s2; s2=s1; s1=s0; } return Math.sqrt(Math.max(0,s1*s1+s2*s2-c*s1*s2))/(e-s)*2; }
console.log('\n== ObservatoryEngine checks (Node) ==\n');
let fails=0; const ck=(ok,n,d='')=>{ console.log(`${n.padEnd(58)} ${ok?'PASS':'**FAIL**'} ${d}`); if(!ok)fails++; };

const SRO = { playing:1, density:60, grainMs:2000, timingJitter:0.5, sizeJitter:0.35, sprayMs:900, reverse:0.3, window:0, spread:0.868, width:1.4, gain:0.8,
  position:0.42, scan:0, chaos:0.14, pitch:0.30, key:3, register:-12, detune:0.06, scale:3, drone:1, space:0.86, shimmer:0.42, tone:0.52 };

// 1. SRO: 60 s, stable, sane level
{ const e=mk(), d=demo(); e.setSeed(0x05700A11,0x0051A9D0); e.setState(SRO,true); e.setSource(d.l,d.r,d.N,2,SRO.position);
  const t0=performance.now(); let maxV=0; const cxs=[];
  const o=run(e,60,(i,en)=>{ if(i%50===0){ const c=en.cloud(); if(c.count>maxV)maxV=c.count; cxs.push(en.cx); } });
  const ms=performance.now()-t0;
  ck(!bad(o.L)&&!bad(o.R),'SRO 60s: finite');
  ck(peak(o.L)<=1&&peak(o.R)<=1,'SRO peak <= 1', `peak ${peak(o.L).toFixed(3)}`);
  const r5=db(rms(o.L,4*sr,6*sr)), r30=db(rms(o.L,28*sr,30*sr)), r60=db(rms(o.L,58*sr,60*sr));
  ck(r60>-30&&r60<-6,'SRO level in a musical range', `rms @5s ${r5.toFixed(1)} @30s ${r30.toFixed(1)} @60s ${r60.toFixed(1)} dBFS`);
  ck(Math.abs(r60-r30)<6,'SRO no runaway build-up 30s->60s');
  ck(maxV>60&&maxV<=256,'SRO voices dense', `max voices ${maxV}`);
  const mn=Math.min(...cxs), mx=Math.max(...cxs);
  ck(mx-mn>0.05,'chaos moves slowly at low DRIFT', `cx range ${mn.toFixed(2)}..${mx.toFixed(2)} over 60s`);
  console.log(`   realtime factor: ${(60000/ms).toFixed(1)}x  (${ms.toFixed(0)} ms for 60 s)`);
  // tail after stop
  e.setState({...SRO,playing:0});
  const t=run(e,30); const tA=db(rms(t.L,2.5*sr,3.5*sr)), tB=db(rms(t.L,29*sr,30*sr));
  ck(tA>-50,'tail rings after grains stop', `tail @3s ${tA.toFixed(1)} dB, @30s ${tB.toFixed(1)} dB`);
  ck(tB<tA-12,'tail decays');
}
// 2. worst case stability: everything maxed for 90 s
{ const e=mk(), d=demo(); e.setState({...SRO, chaos:1, space:1, shimmer:1, tone:1, pitch:1, gain:1.5, density:128},true); e.setSource(d.l,d.r,d.N,2,0.5);
  const o=run(e,90); ck(!bad(o.L)&&peak(o.L)<=1.0,'max space+shimmer+chaos 90s: bounded', `peak ${peak(o.L).toFixed(3)} rms@88s ${db(rms(o.L,86*sr,88*sr)).toFixed(1)} dB`); }
// 3. shimmer adds octave-up energy; reverb adds tail
{ const meas=(sh,space)=>{ const e=mk(), d=sine(220); e.setState({...SRO, drone:0, chaos:0, pitch:0, key:0, register:0, scale:0, detune:0, reverse:0, shimmer:sh, space, tone:1, sprayMs:0, grainMs:400, density:40},true);
    e.setSource(d.l,d.r,d.N,2,0.5); const o=run(e,12); return {f:goertzel(o.L,220,8*sr,12*sr), o1:goertzel(o.L,440,8*sr,12*sr), o2:goertzel(o.L,880,8*sr,12*sr)}; };
  const off=meas(0,0.7), on=meas(0.8,0.7);
  ck(db(on.o1)-db(off.o1)>15,'shimmer: +12 energy appears', `440Hz off ${db(off.o1).toFixed(1)} dB -> on ${db(on.o1).toFixed(1)} dB; 880Hz ${db(off.o2).toFixed(1)} -> ${db(on.o2).toFixed(1)}`);
  const tail=(space)=>{ const e=mk(), d=sine(220); const st={...SRO, drone:0, chaos:0, shimmer:0, space, tone:1, grainMs:200, density:40}; e.setState(st,true); e.setSource(d.l,d.r,d.N,2,0.5); run(e,6); e.setState({...st,playing:0}); const o=run(e,4); return db(rms(o.L,2*sr,3*sr)); };
  const dry=tail(0), wet=tail(0.8);
  ck(dry<-100&&wet>-45,'reverb: space=0 is dry, space=.8 rings', `2-3 s after stop: dry ${dry.toFixed(0)} dB, wet ${wet.toFixed(1)} dB`); }
// 4. scale lock: all grain pitches on the scale
{ const sets=[null,[0,1,2,3,4,5,6,7,8,9,10,11],[0,2,4,5,7,9,11],[0,2,3,5,7,8,10],[0,2,4,7,9],[0,3,5,7,10],[0,7]];
  for(const sc of [2,3,5,6]){ const e=mk(), d=demo(); e.setState({...SRO, scale:sc, key:3, register:0, detune:0, pitch:0.8, chaos:0.6, grainMs:300, density:80},true); e.setSource(d.l,d.r,d.N,2,0.5);
    const seen=new Set(); let off=0,tot=0;
    run(e,10,(i,en)=>{ if(i%20===0){ const c=en.cloud(); for(let k=0;k<c.count;k++){ const st=12*Math.log2(Math.abs(c.data[k*5+1])); const n=Math.round(st); tot++; if(Math.abs(st-n)>0.02||!sets[sc].includes((((n-3)%12)+12)%12)) off++; else seen.add(n); } } });
    ck(off===0&&seen.size>=3,`scale-lock scale#${sc}: every grain in key`, `${tot} grains sampled, ${seen.size} distinct notes, ${off} off-scale`); }
  const e=mk(), d=demo(); e.setState({...SRO, scale:0, pitch:0.6, detune:0},true); e.setSource(d.l,d.r,d.N,2,0.5); let frac=0,tot=0;
  run(e,6,(i,en)=>{ if(i%20===0){ const c=en.cloud(); for(let k=0;k<c.count;k++){ const st=12*Math.log2(Math.abs(c.data[k*5+1])); tot++; if(Math.abs(st-Math.round(st))>0.05) frac++; } } });
  ck(frac/tot>0.5,'free scale: continuous pitch spray', `${(100*frac/tot).toFixed(0)}% off-semitone`); }
// 5. chaos drives position, pitch range and width together; drone latches; position servo
{ const e=mk(), d=demo(130.81,20); e.setState({...SRO, drone:0, chaos:0.9, scan:0, position:0.5},true); e.setSource(d.l,d.r,d.N,2,0.5);
  let pmin=1,pmax=0; const xs=[],ps=[]; run(e,20,(i,en)=>{ const p=en.playhead/en.frames; if(p<pmin)pmin=p; if(p>pmax)pmax=p; if(i%8===0){xs.push(en.cx); ps.push(p);} });
  let mx=0,mp=0; for(let i=0;i<xs.length;i++){mx+=xs[i];mp+=ps[i];} mx/=xs.length; mp/=ps.length; let sxy=0,sxx=0,syy=0; for(let i=0;i<xs.length;i++){ sxy+=(xs[i]-mx)*(ps[i]-mp); sxx+=(xs[i]-mx)**2; syy+=(ps[i]-mp)**2; }
  ck(pmax-pmin>0.05,'chaos sways grain position', `playhead ${pmin.toFixed(3)}..${pmax.toFixed(3)}, corr with chaos x = ${(sxy/Math.sqrt(sxx*syy)).toFixed(2)}`);
  const e2=mk(); e2.setState({...SRO, drone:0, chaos:0, scan:0.5, position:0.2},true); e2.setSource(d.l,d.r,d.N,2,0.2); run(e2,4);
  const moved=e2.playhead/e2.frames; e2.setState({...SRO, drone:1, chaos:0, scan:0.5, position:0.2}); run(e2,4); const held=e2.playhead/e2.frames;
  ck(moved>0.28&&Math.abs(held-moved)<0.03,'scan moves the playhead; drone latches it', `after scan ${moved.toFixed(3)}, after 4 s drone ${held.toFixed(3)}`);
  e2.setState({...SRO, drone:1, chaos:0, position:0.8}); run(e2,2); ck(Math.abs(e2.playhead/e2.frames-0.8)<0.03,'pad X repositions the latched playhead', (e2.playhead/e2.frames).toFixed(3)); }
// 6. determinism of the whole hybrid chain
{ const go=()=>{ const e=mk(), d=demo(); e.setSeed(7,9); e.setState(SRO,true); e.setSource(d.l,d.r,d.N,2,SRO.position); return run(e,5).L; };
  const a=go(), b=go(); let same=true; for(let i=0;i<a.length;i++) if(a[i]!==b[i]){same=false;break;} ck(same,'same seed + state -> bit-identical render (grains + chaos + FX)'); }

// 7. MIDI keyboard notes
{ const e=mk(), d=demo(); e.setState({...SRO, scale:0, pitch:0, detune:0, register:0, key:0, reverse:0, grainMs:300, density:80},true); e.setSource(d.l,d.r,d.N,2,0.5);
  e.setNotes([0,7,-12]); const seen=new Set(); run(e,2); run(e,6,(i,en)=>{ if(i%20===0){ const c=en.cloud(); for(let k=0;k<c.count;k++) seen.add(Math.round(1200*Math.log2(Math.abs(c.data[k*5+1])))/100); } });
  ck([...seen].sort((a,b)=>a-b).join(',')==='-12,0,7','keys (free): grains spread over exactly the held notes', [...seen].join(' '));
  const e2=mk(); e2.setState({...SRO, scale:3, key:3, register:-12, pitch:0.5, detune:0, grainMs:300, density:80},true); e2.setSource(d.l,d.r,d.N,2,0.5);
  e2.setNotes([3,6,10,1]); let off=0,tot=0,lo=99,hi=-99; run(e2,2); run(e2,8,(i,en)=>{ if(i%20===0){ const c=en.cloud(); for(let k=0;k<c.count;k++){ const st=12*Math.log2(Math.abs(c.data[k*5+1])), n=Math.round(st); tot++; lo=Math.min(lo,n); hi=Math.max(hi,n); if(Math.abs(st-n)>0.02||![0,2,3,5,7,8,10].includes((((n-3)%12)+12)%12)) off++; } } });
  ck(off===0&&tot>500,'keys + scale-lock: every grain in E-flat minor (incl. an out-of-key note)', `${tot} grains, ${off} off-scale, range ${lo}..${hi} st`);
  e2.setNotes([]); const s2=new Set(); run(e2,3); run(e2,3,(i,en)=>{ if(i%20===0){ const c=en.cloud(); for(let k=0;k<c.count;k++) s2.add(Math.round(12*Math.log2(Math.abs(c.data[k*5+1])))); } });
  ck(Math.min(...s2)>=-9-12&&Math.max(...s2)<=-9+12&&s2.has(-9),'clearing the chord returns to key + register', [...s2].sort((a,b)=>a-b).join(' ')); }
console.log(fails?`\n${fails} FAILED`:'\nall passed'); process.exit(fails?1:0);
