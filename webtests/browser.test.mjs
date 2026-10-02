// Browser checks for the web app (docs/index.html), driven through headless Chrome.
//   npm install        (once, for puppeteer-core)
//   node browser.test.mjs
// Serves docs/ on a local port itself, so nothing else needs to be running. MIDI, the mic and
// the iPhone are simulated: this proves the page's own logic, not real hardware or real Safari.
// Set CHROME_PATH if Chrome is not in the usual macOS location.
import puppeteer, { KnownDevices } from 'puppeteer-core';
import fs from 'fs';
import os from 'os';
import path from 'path';
import http from 'http';
import { fileURLToPath } from 'url';

const DOCS = fileURLToPath(new URL('../docs/', import.meta.url));
const CHROME = process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const TMP = fs.mkdtempSync(path.join(os.tmpdir(), 'grv-webtests-'));
const sleep = ms => new Promise(r => setTimeout(r, ms));

let fails = 0;
const R = (name, ok, detail='') => { console.log(`${name.padEnd(58)} ${ok ? 'PASS' : '**FAIL**'} ${detail}`); if (!ok) fails++; };
const section = t => console.log(`\n-- ${t} --`);

// ---- static server for docs/ ----
const TYPES = { '.html':'text/html', '.m4a':'audio/mp4', '.wav':'audio/wav', '.md':'text/markdown' };
const server = http.createServer((req, res) => {
  const rel = decodeURIComponent(req.url.split('?')[0]);
  const file = path.join(DOCS, rel === '/' ? 'index.html' : rel);
  if (!file.startsWith(DOCS) || !fs.existsSync(file) || fs.statSync(file).isDirectory()){ res.writeHead(404); res.end(); return; }
  res.writeHead(200, { 'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream' });
  fs.createReadStream(file).pipe(res);
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const URL_ = `http://localhost:${server.address().port}/index.html`;

const browser = await puppeteer.launch({ executablePath: CHROME, headless: true, args: [
  '--autoplay-policy=no-user-gesture-required', '--use-angle=metal', '--enable-gpu', '--ignore-gpu-blocklist', '--mute-audio',
  '--window-size=1440,810', '--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream' ] });

const problems = [];
async function openPage({ device, fakeMidi = true } = {}){
  const page = await browser.newPage();
  if (device) await page.emulate(KnownDevices[device]); else await page.setViewport({ width:1440, height:810, deviceScaleFactor:1 });
  page.on('pageerror', e => problems.push('page error: ' + e.message));
  page.on('console', m => { if (m.type()==='error' && !/favicon|ERR_FAILED/.test(m.text() + (m.location().url||''))) problems.push('console error: ' + m.text()); });
  if (fakeMidi) await page.evaluateOnNewDocument(() => { const inp = { name:'Fake MPK', onmidimessage:null }; window.__midiIn = inp;
    navigator.requestMIDIAccess = async () => ({ inputs:new Map([['a', inp]]), onstatechange:null }); });
  await page.goto(URL_, { waitUntil:'load' });
  return page;
}
const stats = page => page.evaluate(() => window.__grv.stats());
const text = (page, sel) => page.$eval(sel, e => e.textContent);
const midi = (page, ...d) => page.evaluate(d => window.__midiIn.onmidimessage({ data:d }), d);
const semis = async page => [...new Set((await page.evaluate(() => window.__grv.rates())).map(r => Math.round(12*Math.log2(Math.abs(r)))))].sort((a,b)=>a-b);

// a short WAV to import (1 s, 220 Hz, 16-bit mono)
const TONE = path.join(TMP, 'tone.wav');
{ const sr = 48000, n = sr, b = Buffer.alloc(44 + n*2);
  b.write('RIFF',0); b.writeUInt32LE(36+n*2,4); b.write('WAVEfmt ',8); b.writeUInt32LE(16,16); b.writeUInt16LE(1,20); b.writeUInt16LE(1,22);
  b.writeUInt32LE(sr,24); b.writeUInt32LE(sr*2,28); b.writeUInt16LE(2,32); b.writeUInt16LE(16,34); b.write('data',36); b.writeUInt32LE(n*2,40);
  for (let i=0;i<n;i++) b.writeInt16LE(Math.round(12000*Math.sin(2*Math.PI*220*i/sr)), 44+i*2);
  fs.writeFileSync(TONE, b); }

try {
  // ============ 1. start, controls, MIDI-learn, presets, export, import, mic ============
  section('start, controls, MIDI-learn, presets, export, import, mic');
  {
    const page = await openPage();
    const cdp = await page.createCDPSession(); await cdp.send('Browser.setDownloadBehavior', { behavior:'allow', downloadPath:TMP });
    await page.click('#enter'); await sleep(6000);
    let s = await stats(page);
    R('default preset auto-plays', s.playing && s.hasSource && s.voices > 60 && s.ctx === 'running', JSON.stringify({ voices:s.voices, fps:+s.fps.toFixed(1), kind:s.kind }));
    R('default is Standing Room Only', await text(page, '#presetTitle') === 'Standing Room Only');
    R('renders with WebGL at a healthy frame rate', s.kind === 'webgl' && s.fps > 45, s.fps.toFixed(1) + ' fps');
    await page.focus('#ring_m_space'); for (let i=0;i<5;i++) await page.keyboard.press('ArrowDown');
    R('ring keyboard moves SPACE', Math.abs(await page.$eval('#m_space', e => +e.value) - 0.76) < 0.001);
    const pad = await (await page.$('#pad')).boundingBox();
    await page.mouse.move(pad.x+pad.width*0.2, pad.y+pad.height*0.3); await page.mouse.down();
    await page.mouse.move(pad.x+pad.width*0.75, pad.y+pad.height*0.25, { steps:8 }); await page.mouse.up();
    R('XY pad sets position + tone', Math.abs(await page.$eval('#p_position', e => +e.value) - 0.75) < 0.02 && Math.abs(await page.$eval('#p_tone', e => +e.value) - 0.75) < 0.03);
    await page.click('#drone'); R('drone toggles off', await page.$eval('#drone', e => !e.classList.contains('on'))); await page.click('#drone');
    await page.click('#midi'); await sleep(200); await page.click('#learn'); await sleep(100);
    await page.click('#badge_m_drift'); await midi(page, 0xB0, 21, 64);
    R('MIDI learn binds CC21 -> DRIFT', await text(page, '#badge_m_drift') === 'CC21');
    await page.click('#drone'); await midi(page, 0x90, 40, 100); await page.click('#learn');
    await midi(page, 0xB0, 21, 127);
    R('bound knob drives the ring', await page.$eval('#m_drift', e => +e.value) === 1);
    const d0 = await page.$eval('#drone', e => e.classList.contains('on')); await midi(page, 0x90, 40, 100); await midi(page, 0x80, 40, 0);
    R('bound pad toggles DRONE, and does not play a pitch', d0 !== await page.$eval('#drone', e => e.classList.contains('on')) && (await page.evaluate(() => window.__grv.chord())).length === 0);
    R('MIDI map persisted', (await page.evaluate(() => localStorage.getItem('grvr.midimap.v1'))).includes('"21":"m_drift"'));
    for (const i of [1,2,3,0]){ await page.select('#preset', String(i)); await sleep(3500); s = await stats(page);
      R('preset ' + (i+1) + ' ' + await text(page, '#presetTitle'), s.playing && s.voices > 0, JSON.stringify({ voices:s.voices, fps:+s.fps.toFixed(1) })); }
    await page.click('#export');
    let f = null; for (let i=0; i<120 && !f; i++){ await sleep(500); f = fs.readdirSync(TMP).find(n => n.startsWith('GR_') && n.endsWith('.wav')); }
    await sleep(800);
    if (f){
      const b = fs.readFileSync(path.join(TMP, f)), sr = b.readUInt32LE(24), ch = b.readUInt16LE(22), n = (b.length-44)/4/ch;
      const fl = new Float32Array(b.buffer.slice(b.byteOffset+44, b.byteOffset+44+n*ch*4));
      const rms = (a0, a1) => { let t = 0; for (let i=Math.floor(a0*sr); i<Math.floor(a1*sr); i++) t += fl[i*ch]**2; return 10*Math.log10(t/((a1-a0)*sr) + 1e-20); };
      let nan = 0; for (const v of fl) if (!Number.isFinite(v)) nan++;
      const dur = n/sr;
      R('export downloads a WAV', true, `${f}  ${sr} Hz, ${ch} ch, ${dur.toFixed(1)} s`);
      R('export includes the FX tail past the 8 s body', dur > 10 && nan === 0 && rms(9.5, 10.5) > -60, `tail (9.5-10.5 s) ${rms(9.5,10.5).toFixed(1)} dB, last 0.2 s ${rms(dur-0.2,dur).toFixed(1)} dB`);
      await (await page.$('#file')).uploadFile(path.join(TMP, f)); await sleep(4000);
      R('the exported WAV imports and plays', await text(page, '#srcname') === f && (await stats(page)).voices > 0);
    } else R('export downloads a WAV', false, await text(page, '#status'));
    await page.click('#mic'); await sleep(2500); await page.click('#mic'); await sleep(1500);
    R('mic record -> source', await text(page, '#srcname') === 'mic take' && (await stats(page)).voices > 0);
    await page.close();
  }

  // ============ 2. MIDI keyboard ============
  section('MIDI keyboard');
  {
    const page = await openPage();
    await page.click('#enter'); await sleep(2500);
    await page.select('#preset', '3'); await sleep(300);                    // Bare Grains: free scale, register 0
    await page.evaluate(() => { const e = document.getElementById('m_pitch'); e.value = 0; e.dispatchEvent(new Event('input')); });
    await page.click('#midi'); await sleep(300);
    await midi(page, 0x90, 60, 100); await midi(page, 0x90, 67, 100); await sleep(2500);
    R('held C4+G4 -> grains at 0 and +7 st', (await semis(page)).join(',') === '0,7', (await semis(page)).join(' '));
    await midi(page, 0x80, 60, 0); await midi(page, 0x90, 67, 0); await sleep(2000);
    R('latch: chord stays after release', (await semis(page)).join(',') === '0,7' && (await stats(page)).voices > 0);
    await midi(page, 0x90, 48, 90); await sleep(2500); await midi(page, 0x80, 48, 0);
    R('latch: a new press replaces the chord (C3 = -12)', (await semis(page)).join(',') === '-12');
    R('readout shows the chord', (await text(page, '#hud')).includes('keys C3'));
    await page.click('#detailBtn'); await sleep(500); await page.select('#keysMode', 'gate'); await sleep(1800);
    R('gate: silent with no keys held', (await stats(page)).voices === 0);
    await midi(page, 0x90, 63, 100); await midi(page, 0x90, 70, 100); await sleep(1500);
    R('gate: held notes sound (+3, +10)', (await semis(page)).join(',') === '3,10' && (await stats(page)).voices > 0);
    await midi(page, 0xB0, 64, 127); await midi(page, 0x80, 63, 0); await midi(page, 0x80, 70, 0); await sleep(1500);
    R('gate: sustain pedal holds the chord', (await stats(page)).voices > 0 && (await semis(page)).join(',') === '3,10');
    await midi(page, 0xB0, 64, 0); await sleep(1500);
    R('gate: pedal up closes the gate', (await stats(page)).voices === 0);
    await page.select('#keysMode', 'latch'); await sleep(1200);
    R('back to latch: cloud resumes', (await stats(page)).voices > 0);
    await page.select('#preset', '0'); await sleep(500);
    R('preset change clears the chord', (await page.evaluate(() => window.__grv.chord())).length === 0);
    await midi(page, 0x90, 61, 100); await midi(page, 0x80, 61, 0); await sleep(6000);   // C#4 is outside E-flat minor
    const sm = await semis(page);
    R('scale-lock + keys: all grains in E-flat minor', sm.length > 1 && sm.every(n => [0,2,3,5,7,8,10].includes((((n-3)%12)+12)%12)), sm.join(' '));
    await page.close();
  }

  // ============ 3. sample library menu ============
  section('sample library');
  {
    const page = await openPage();
    const shown = () => page.$eval('#library', e => e.options[e.selectedIndex].text);
    R('before anything loads: "Samples…"', await shown() === 'Samples…');
    await page.click('#enter'); await sleep(3500);
    R('Enter loads the demo: menu shows "Demo pad (C)"', await shown() === 'Demo pad (C)');
    await page.select('#library', '1'); await sleep(5000);
    const src = await page.evaluate(() => { const s = window.__grv.source(); return { secs:s.frames/48000, ch:s.channels }; });
    R('Big River loads from the library and plays', await shown() === 'Big River' && await text(page, '#srcname') === 'Big River' && src.ch === 2 && src.secs > 47 && (await stats(page)).voices > 0, `${src.secs.toFixed(1)} s, ${src.ch} ch`);
    await (await page.$('#file')).uploadFile(TONE); await sleep(3000);
    R('import a file: menu returns to "Samples…"', await shown() === 'Samples…' && await text(page, '#srcname') === 'tone.wav');
    await page.select('#library', '0'); await sleep(3000);
    R('back to the demo from the menu', await shown() === 'Demo pad (C)');
    await page.setRequestInterception(true); page.on('request', r => r.url().includes('/samples/') ? r.abort() : r.continue());
    await page.select('#library', '1'); await sleep(2500);
    R('failed load: menu stays on the loaded sample', await shown() === 'Demo pad (C)', await text(page, '#status'));
    await page.close();
  }

  // ============ 4. help panel + start-screen tips ============
  section('help');
  {
    const page = await openPage();
    await sleep(1200);
    const open = () => page.$eval('#help', e => e.classList.contains('open'));
    R('desktop: iOS warning hidden on the start screen', await page.$eval('#iosTip', e => e.hidden));
    await page.click('#gateHelp'); await sleep(500);
    R('"How to play" opens help and focuses Close', await open() && await page.evaluate(() => document.activeElement.id === 'helpClose'));
    await page.keyboard.press('Escape'); await sleep(400); R('Esc closes help', !(await open()));
    await page.click('#enter'); await sleep(3500);
    await page.click('#helpBtn'); await sleep(400); R('Help button opens it while playing', await open());
    await page.mouse.click(8, 400); await sleep(400);
    R('clicking outside closes it; audio keeps playing', !(await open()) && (await stats(page)).playing);
    await page.close();
    const phone = await openPage({ device:'iPhone 13', fakeMidi:false });
    await sleep(1200);
    R('iPhone: silent-switch warning on the start screen', !(await phone.$eval('#iosTip', e => e.hidden)));
    await phone.tap('#gateHelp'); await sleep(500);
    R('iPhone: silent-switch tip flagged in help', await phone.$eval('#helpIos', e => e.classList.contains('flag')));
    R('iPhone: help fits the screen and scrolls', await phone.evaluate(() => { const el = document.querySelector('.helpbox'), b = el.getBoundingClientRect();
      return b.width <= innerWidth && b.height <= innerHeight && el.scrollHeight > b.height; }));
    await phone.close();
  }

  // ============ 5. Canvas2D fallback ============
  section('fallback renderer');
  {
    const page = await browser.newPage(); await page.setViewport({ width:1280, height:720, deviceScaleFactor:1 });
    page.on('pageerror', e => problems.push('page error (2d): ' + e.message));
    await page.goto(URL_ + '?2d', { waitUntil:'load' }); await page.click('#enter'); await sleep(5000);
    const s = await stats(page);
    R('?2d: Canvas2D renderer runs the same scene', s.kind === 'canvas2d' && s.voices > 60 && s.fps > 30, JSON.stringify({ kind:s.kind, voices:s.voices, fps:+s.fps.toFixed(1) }));
    await page.close();
  }

  section('page health');
  R('no page errors or console errors', problems.length === 0, problems.join(' | '));
} finally {
  await browser.close();
  server.close();
  fs.rmSync(TMP, { recursive:true, force:true });
}
console.log(fails ? `\n${fails} FAILED` : '\nall passed');
process.exit(fails ? 1 : 0);
