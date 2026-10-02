// The WebAssembly half of the parity check: see test_parity.cpp.
import fs from 'fs';
const ref = fs.readFileSync(process.argv[2] || '/tmp/parity.raw');
const e = new WebAssembly.Instance(new WebAssembly.Module(fs.readFileSync(new URL('../grooverider.wasm', import.meta.url))), {}).exports;
const sr = 48000, dur = 6, N = sr*dur;
e.grv_init(sr);
const l = new Float32Array(e.memory.buffer, e.grv_src_l_ptr(), N), r = new Float32Array(e.memory.buffer, e.grv_src_r_ptr(), N);
for (let h=1;h<=6;h++){ const f = Math.round(130.81*h*dur)/dur; for (let i=0;i<N;i++) l[i] += 0.25/h*Math.sin(2*Math.PI*f*i/sr+h); }
for (let i=0;i<N;i++) r[i] = l[i]*Math.fround(0.9);
e.grv_set_seed(0x05700A11, 0x0051A9D0);
for (const [id,v] of [[0,60],[1,0.5],[2,2000],[3,0.35],[9,0.3],[11,0],[13,0.8],[14,1],[15,1]]) e.grv_set_param_now(id, v);
[0.42,0,900,0.868,1.4,0.14,0.30,3,3,-12,0.06,1,0.86,0.42,0.52].forEach((v,i)=>e.grv_obs_param(i, v));
e.grv_set_param_now(4, 0.42); e.grv_set_source(2, N); e.grv_obs_source_changed(0.42);
const out = new Uint8Array(e.memory.buffer, e.grv_out_ptr(), 256*4), blocks = Math.floor(sr*30/128);
let differing = 0, peak = 0; const f32 = new Float32Array(e.memory.buffer, e.grv_out_ptr(), 256);
for (let b=0;b<blocks;b++){
  if (b === 4000){ new Float32Array(e.memory.buffer, e.grv_notes_ptr(), 3).set([0,7,-12]); e.grv_obs_set_notes(3); }
  if (b === 8000){ e.grv_obs_param(11, 0); e.grv_obs_param(1, 0.2); e.grv_obs_param(5, 0.8); }
  e.grv_obs_render(128);
  const want = ref.subarray(b*1024, b*1024+1024);
  for (let i=0;i<1024;i++) if (out[i] !== want[i]){ differing++; break; }
  for (const v of f32) peak = Math.max(peak, Math.abs(v));
}
const ok = differing === 0 && ref.length === blocks*1024 && peak > 0.1;
console.log(`native vs wasm, 30 s of Standing Room Only with a chord and a patch change: ${ok ? 'BIT-IDENTICAL  PASS' : '**FAIL** ('+differing+' of '+blocks+' blocks differ)'}`);
process.exit(ok ? 0 : 1);
