// Hum → MIDI checks (docs/index.html), run in Node. No browser needed:  node a2m.test.mjs
// The A2M analysis is lifted out of the page between its A2M-BEGIN / A2M-END markers, so this
// tests exactly what ships. Takes are synthesised from a known melody so the answer is known.
// Optional: A2M_WAV=/path/to/hum.wav node a2m.test.mjs  also prints what a real take turns into.
import fs from 'fs';
const html = fs.readFileSync(new URL('../docs/index.html', import.meta.url), 'utf8');
const A2M = new Function(html.split('/*A2M-BEGIN*/')[1].split('/*A2M-END*/')[0] + '; return A2M;')();

console.log('\n== Hum → MIDI checks (Node) ==\n');
let fails = 0; const ck = (ok, n, d='') => { console.log(`${n.padEnd(58)} ${ok ? 'PASS' : '**FAIL**'} ${d}`); if (!ok) fails++; };

// ---- a hum-like voice: harmonics, vibrato, scoops and slides, breath noise, quiet like a phone mic ----
const sr = 48000;
let rng = 12345; const rand = () => ((rng = Math.imul(rng ^ (rng >>> 15), 2246822519) + 0x9E3779B9 | 0) >>> 0) / 4294967296;
function hum(notes, { level = 0.06, noise = 0.002, tail = 0.5, legato = false } = {}){
  const end = notes[notes.length-1].t + notes[notes.length-1].dur + tail, N = Math.ceil(end*sr), x = new Float32Array(N);
  let ph = 0, prev = notes[0].p;
  for (let i = 0; i < N; i++) x[i] = noise * (rand()*2 - 1);
  for (const [k, n] of notes.entries()){
    const a = Math.floor(n.t*sr), b = Math.floor((n.t + n.dur)*sr), from = n.from ?? (legato ? prev : n.p - 0.7), glide = n.glide ?? 0.045;
    if (!legato) ph = 0;
    for (let i = a; i < b && i < N; i++){
      const t = (i - a)/sr, left = (b - i)/sr;
      const p = t < glide ? from + (n.p - from) * (t/glide) : n.p;
      const f = 440 * Math.pow(2, (p + 0.2*Math.sin(2*Math.PI*5.5*t) - 69)/12);
      ph += 2*Math.PI*f/sr;
      // Legato: one breath, so only the first note fades in and only the last fades out.
      const env = (legato && k > 0 ? 1 : Math.min(1, t/0.015)) * (legato && k < notes.length-1 ? 1 : Math.min(1, left/0.03)) * (n.v ?? 1);
      let s = 0; for (let h = 1; h <= 8; h++) s += Math.sin(h*ph) / Math.pow(h, 1.3);
      x[i] += level * env * s;
    }
    prev = n.p;
  }
  return x;
}
const NAMES = ['C','C#','D','Eb','E','F','F#','G','Ab','A','Bb','B'], nn = p => NAMES[(p%12+12)%12] + (Math.floor(p/12) - 1);

// A drum & bass-ish hummed line at 166 BPM in A minor. Times on the 16th grid, sung slightly loose.
const BPM = 166, S16 = 60/BPM/4, LEAD = 0.6;
const LINE = [ [0,3,57],[4,2,57],[6,2,60],[8,4,64],[14,2,62],[16,3,60],[20,2,59],[22,2,57],[24,6,52],[32,3,57],[36,2,57],[38,2,55],[40,8,57] ];
const sungLine = LINE.map(([s, len, p], i) => ({ t: LEAD + s*S16 + (rand()-0.5)*0.024, dur: len*S16*0.86, p, v: 0.75 + 0.25*rand() }));

{ // 1. detached hum: every note recovered, on time, in tune
  const x = hum(sungLine), t = performance.now(), r = A2M.analyse(x, sr), ms = performance.now() - t;
  const got = r.notes.map(n => n.pitch), want = LINE.map(l => l[2]);
  ck(got.length === want.length && got.every((p, i) => p === want[i]), 'detached hum: every note, right pitch', got.map(nn).join(' '));
  const err = r.notes.length === sungLine.length ? Math.max(...r.notes.map((n, i) => Math.abs(n.start - sungLine[i].t))) : 1;
  ck(err < 0.035, 'detached hum: onsets within 35 ms', `worst ${(err*1000).toFixed(0)} ms`);
  ck(ms < (x.length/sr) * 100, 'analysis is fast', `${ms.toFixed(0)} ms for ${(x.length/sr).toFixed(1)} s of audio`);

  // tempo, key and quantise from that take
  const bpm = A2M.estimateTempo(r.notes);
  ck(Math.abs(bpm - BPM) / BPM < 0.02 || Math.abs(bpm*2 - BPM) / BPM < 0.02, 'tempo detected (or its half)', `${bpm} BPM`);
  const k = A2M.detectKey(r.notes);
  ck(k.tonic === 9 && k.minor || k.tonic === 0 && !k.minor, 'key: A minor (or its relative C major)', `${NAMES[k.tonic]} ${k.minor ? 'minor' : 'major'}`);
  const t0 = A2M.alignStart(r.notes, BPM, r.notes[0].start), q = A2M.toTicks(r.notes, { bpm: BPM, grid: 16, t0 });
  const g = 120;   // 480 ppq: a 16th is 120 ticks
  ck(q.notes.length === LINE.length && q.notes.every((n, i) => n.on === LINE[i][0]*g), 'quantised to 1/16: every note on its grid step', q.notes.map(n => n.on/g).join(','));
  ck(q.notes.every((n, i) => i === 0 || n.on >= q.notes[i-1].off), 'quantised notes never overlap');
  const loose = A2M.toTicks(r.notes, { bpm: BPM, grid: 16, strength: 0, t0 });
  ck(loose.notes.some(n => n.on % g !== 0), 'strength 0 keeps the human timing');
}

{ // 2. legato hum: slides between notes become the note they land on, not a run of blips
  // Each note runs right up to the next, so the voice never stops.
  const x = hum(LINE.slice(0, 8).map(([s, len, p], i) => ({ t: LEAD + s*S16, dur: (i < 7 ? LINE[i+1][0] - s : len)*S16, p, glide: 0.07 })), { legato: true });
  const got = A2M.analyse(x, sr).notes.map(n => n.pitch), want = LINE.slice(0, 8).map(l => l[2]);
  // Notes 1–2 are the same pitch sung through without a break: one long note is the honest answer.
  const merged = want.filter((p, i) => i === 0 || p !== want[i-1]);
  ck(JSON.stringify(got) === JSON.stringify(merged), 'legato slides: one note per pitch, no blips', got.map(nn).join(' '));
}

{ // 3. "dum-dum": the same pitch re-sung with a dip in level becomes two notes
  const x = hum([{ t: 0.3, dur: 0.25, p: 50 }, { t: 0.57, dur: 0.25, p: 50 }, { t: 0.84, dur: 0.3, p: 50 }], { legato: true });
  const got = A2M.analyse(x, sr).notes;
  ck(got.length === 3 && got.every(n => n.pitch === 50), 'repeated notes with a dip split', got.map(n => nn(n.pitch) + '@' + n.start.toFixed(2)).join(' '));
}

{ // 4. range: low bass hum to a whistle
  const x = hum([{ t: 0.2, dur: 0.4, p: 36 }, { t: 0.8, dur: 0.4, p: 45 }, { t: 1.4, dur: 0.4, p: 72 }, { t: 2.0, dur: 0.4, p: 84 }]);
  const got = A2M.analyse(x, sr).notes.map(n => n.pitch);
  ck(JSON.stringify(got) === '[36,45,72,84]', 'range C2 to C6', got.map(nn).join(' '));
}

{ // 5. level: a very quiet take still works, and silence / noise give nothing
  const quiet = A2M.analyse(hum(sungLine, { level: 0.004, noise: 0.0001 }), sr).notes;
  ck(quiet.length === LINE.length, 'quiet take (−50 dBFS peaks) still found', `${quiet.length} notes`);
  ck(A2M.analyse(new Float32Array(sr*2), sr).notes.length === 0, 'silence gives no notes');
  const nz = new Float32Array(sr*2); for (let i = 0; i < nz.length; i++) nz[i] = 0.05*(rand()*2 - 1);
  ck(A2M.analyse(nz, sr).notes.length === 0, 'white noise gives no notes');
  ck(A2M.analyse(hum(sungLine), 44100).notes.length === LINE.length, '44.1 kHz input works');
}

{ // 6. key snap: a flat-sung note lands in the scale by its sung pitch
  const notes = [{ start: 0, dur: 0.3, pitch: 61, cents: 60.6, vel: 90 }, { start: 0.3, dur: 0.3, pitch: 63, cents: 63.4, vel: 90 }];
  const sn = A2M.snapToScale(notes, 0, [0,2,4,5,7,9,11]).map(n => n.pitch);
  ck(sn[0] === 60 && sn[1] === 64, 'snap to C major uses the sung pitch', sn.map(nn).join(' '));
}

{ // 7. MIDI file: a valid SMF that reads back to the same notes and tempo
  const t = { ppq: 480, bpm: 166, notes: [{ on: 0, off: 240, pitch: 57, vel: 100 }, { on: 240, off: 480, pitch: 60, vel: 80 }, { on: 960, off: 2400, pitch: 64, vel: 127 }] };
  const b = A2M.writeMidi(t, 'test'), dv = new DataView(b.buffer);
  const id = o => String.fromCharCode(b[o], b[o+1], b[o+2], b[o+3]);
  let ok = id(0) === 'MThd' && dv.getUint32(4) === 6 && dv.getUint16(8) === 0 && dv.getUint16(10) === 1 && dv.getUint16(12) === 480 && id(14) === 'MTrk';
  const end = 22 + dv.getUint32(18); ok = ok && end === b.length;
  let o = 22, tick = 0, tempo = 0, run = 0; const on = {}, back = [];
  const vlq = () => { let v = 0, c; do { c = b[o++]; v = (v << 7) | (c & 127); } while (c & 128); return v; };
  while (o < end){
    tick += vlq(); let st = b[o];
    if (st === 0xFF){ const ty = b[o+1]; o += 2; const len = vlq(); if (ty === 0x51) tempo = (b[o]<<16)|(b[o+1]<<8)|b[o+2]; o += len; continue; }
    if (st & 0x80){ run = st; o++; } st = run;
    const p = b[o++], v = b[o++];
    if ((st & 0xF0) === 0x90 && v > 0) on[p] = [tick, v]; else { back.push({ on: on[p][0], off: tick, pitch: p, vel: on[p][1] }); delete on[p]; }
  }
  back.sort((a, c) => a.on - c.on);
  ck(ok && Math.round(60e6/tempo) === 166 && JSON.stringify(back) === JSON.stringify(t.notes), 'MIDI file reads back: header, tempo, notes', `${b.length} bytes`);
}

// ---- optional: a real take ----
if (process.env.A2M_WAV){
  const buf = fs.readFileSync(process.env.A2M_WAV); let off = 12, rate = 0, ch = 1, bits = 16, data;
  while (off < buf.length){ const id = buf.toString('ascii', off, off+4), sz = buf.readUInt32LE(off+4);
    if (id === 'fmt '){ ch = buf.readUInt16LE(off+10); rate = buf.readUInt32LE(off+12); bits = buf.readUInt16LE(off+22); }
    if (id === 'data') data = buf.subarray(off+8, off+8+sz); off += 8 + sz + (sz & 1); }
  if (bits !== 16) console.log('\n(A2M_WAV: only 16-bit PCM WAV is read here)');
  else {
    const n = data.length / 2 / ch, x = new Float32Array(n);
    for (let i = 0; i < n; i++) for (let c = 0; c < ch; c++) x[i] += data.readInt16LE((i*ch + c)*2) / 32768 / ch;
    const r = A2M.analyse(x, rate), k = A2M.detectKey(r.notes);
    console.log(`\n${process.env.A2M_WAV}: ${r.notes.length} notes, ~${A2M.estimateTempo(r.notes)} BPM, ${NAMES[k.tonic]} ${k.minor ? 'minor' : 'major'}`);
    console.log(r.notes.map(n => `${nn(n.pitch)}@${n.start.toFixed(2)}`).join(' '));
  }
}

console.log(fails ? `\n${fails} FAILED` : '\nall passed');
process.exit(fails ? 1 : 0);
