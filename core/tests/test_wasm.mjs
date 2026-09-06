import fs from 'fs';
const bytes = fs.readFileSync('./grooverider.wasm');

// what does it import? (should be nothing — fully self-contained)
const mod = new WebAssembly.Module(bytes);
const imports = WebAssembly.Module.imports(mod);
console.log('wasm imports:', imports.length ? imports.map(i=>`${i.module}.${i.name}`).join(', ') : '(none — self-contained)');

const P = { DENSITY:0, GRAIN_MS:2, POSITION:4, SPRAY_MS:5, DRIFT:6, PITCH:7, PITCH_SPRAY:8,
            REVERSE_PROB:9, SPREAD:10, OUT_GAIN:13, PLAYING:14 };

function makeInstance(){
  const inst = new WebAssembly.Instance(mod, {});
  return inst.exports;
}

function loadTone(e, mem, f, sec){
  const N = Math.floor(48000*sec);
  const l = new Float32Array(mem.buffer, e.grv_src_l_ptr(), N);
  const r = new Float32Array(mem.buffer, e.grv_src_r_ptr(), N);
  for (let i=0;i<N;i++){ const v=0.5*Math.sin(2*Math.PI*f*i/48000); l[i]=v; r[i]=v; }
  e.grv_set_source(2, N);
  return N;
}
function render(e, mem, blocks, F=128){
  const out=[];
  const view = new Float32Array(mem.buffer, e.grv_out_ptr(), F*2);
  for(let b=0;b<blocks;b++){ e.grv_render(F); for(let i=0;i<F*2;i++) out.push(view[i]); }
  return out;
}
const peak = a => a.reduce((m,v)=>Math.max(m,Math.abs(v)),0);
const rmsdb = a => 20*Math.log10(Math.sqrt(a.reduce((s,v)=>s+v*v,0)/a.length)+1e-12);
const maxstep = a => { let m=0; for(let i=2;i<a.length;i+=2) m=Math.max(m,Math.abs(a[i]-a[i-2])); return m; };

let fails=0; const ck=(ok,n,d='')=>{ console.log(`${n.padEnd(50)} ${ok?'PASS':'**FAIL**'} ${d}`); if(!ok)fails++; };

console.log('\n== GrainCore WASM checks (in Node) ==\n');

const e = makeInstance();
const mem = e.memory;
e.grv_init(48000);
loadTone(e, mem, 220, 2.0);

let pre = render(e, mem, 40);
ck(peak(pre) < 1e-6, 'silent until playing');

e.grv_set_param_now(P.PLAYING, 1);
render(e, mem, 80);
let pad = render(e, mem, 400);
ck(peak(pad) > 0.05 && peak(pad) < 1.2, 'produces bounded audio', `(peak ${peak(pad).toFixed(3)})`);
ck(maxstep(pad) < 0.5, 'click-free', `(max step ${maxstep(pad).toFixed(4)})`);

// gain comp flat
e.grv_set_param_now(P.DENSITY,10); render(e,mem,60); let lo=rmsdb(render(e,mem,300));
e.grv_set_param_now(P.DENSITY,160); render(e,mem,60); let hi=rmsdb(render(e,mem,300));
ck(Math.abs(hi-lo)<3.0, 'gain comp: density flat', `(${lo.toFixed(1)} vs ${hi.toFixed(1)} dB)`);

// determinism across two independent instances
function fullRun(){
  const x=makeInstance(); x.grv_init(48000);
  const N=Math.floor(48000*2); const l=new Float32Array(x.memory.buffer,x.grv_src_l_ptr(),N);
  const r=new Float32Array(x.memory.buffer,x.grv_src_r_ptr(),N);
  for(let i=0;i<N;i++){const v=0.5*Math.sin(2*Math.PI*220*i/48000);l[i]=v;r[i]=v;}
  x.grv_set_source(2,N); x.grv_set_seed(0x9ABCDEF0,0x12345678); x.grv_set_param_now(P.PLAYING,1);
  return render(x,x.memory,400);
}
const a=fullRun(), b=fullRun();
let ident = a.length===b.length && a.every((v,i)=>v===b[i]);
ck(ident, 'two instances, same seed -> identical');

// cloud snapshot
e.grv_set_param_now(P.DENSITY,60); render(e,mem,120);
const n = e.grv_fill_cloud(256);
const cloud = new Float32Array(mem.buffer, e.grv_cloud_ptr(), n*5);
let inrange=true; for(let i=0;i<n;i++){ if(cloud[i*5]<-.01||cloud[i*5]>1.01) inrange=false; }
ck(n>0 && inrange, 'cloud snapshot valid', `(${n} grains)`);

console.log(`\n${fails?'FAILURES':'ALL PASS'} (${fails} failure${fails===1?'':'s'})\n`);
process.exit(fails?1:0);
