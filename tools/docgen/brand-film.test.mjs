import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
const source = fs.readFileSync(new URL('../../guide/assets/brand-film-v2.js', import.meta.url), 'utf8');
function setup(reduced, play = async () => {}) {
  const media = { matches: reduced, addEventListener(type, fn) { this.change = fn; } };
  const video = { muted: false, plays: 0, pauses: 0, play() { this.plays++; return play(); }, pause() { this.pauses++; } };
  const done = vm.runInNewContext(`(async () => { ${source} })()`, { document: { querySelector: () => video }, window: { matchMedia: () => media } });
  return { media, video, done };
}
test('reduced motion leaves playback to native controls, including after preference is disabled', async () => {
  const s = setup(true); await s.done;
  assert.equal(s.video.plays, 0);
  s.media.matches = false; s.media.change();
  assert.equal(s.video.plays, 0);
  const html = fs.readFileSync(new URL('../../guide/index.md', import.meta.url), 'utf8');
  assert.doesNotMatch(html.match(/<video\b[^>]*>/)[0], /\bautoplay\b/);
  assert.match(html.match(/<video\b[^>]*>/)[0], /\bcontrols\b/);
});
test('enabling reduced motion pauses an already playing video', async () => {
  const s = setup(false); await s.done;
  assert.equal(s.video.plays, 1);
  s.media.matches = true; s.media.change();
  assert.equal(s.video.pauses, 1);
});
test('autoplay rejection retries muted only while motion remains allowed', async () => {
  let reject;
  const s = setup(false, () => new Promise((resolve, fail) => { reject = fail; }));
  s.media.matches = true; s.media.change();
  reject({name: 'NotAllowedError'}); await s.done;
  assert.equal(s.video.plays, 1);
  assert.equal(s.video.muted, false);
  let calls = 0;
  const allowed = setup(false, async () => { if (++calls === 1) throw {name: 'NotAllowedError'}; });
  await allowed.done;
  assert.equal(allowed.video.plays, 2);
  assert.equal(allowed.video.muted, true);
});
test('blocked playback keeps controls without an unhandled rejection', async () => {
  const s = setup(false, async () => { throw {name: 'NotAllowedError'}; });
  await s.done;
  assert.equal(s.video.plays, 2);
});
