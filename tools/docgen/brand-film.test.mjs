import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

test('home video waits for explicit playback without eagerly fetching media', () => {
  const html = fs.readFileSync(new URL('../../docs/index.html', import.meta.url), 'utf8');
  const video = html.match(/<video\b[^>]*>/)[0];
  assert.match(video, /\bcontrols\b/);
  assert.match(video, /preload="none"/);
  assert.doesNotMatch(video, /\bautoplay\b/);
  assert.doesNotMatch(html, /brand-film.*\.js/);
});

test('mobile film selection stays lazy and does not restart on rotation', () => {
  const script = fs.readFileSync(new URL('../../guide/assets/video-variant.js', import.meta.url), 'utf8');
  const html = fs.readFileSync(new URL('../../docs/index.html', import.meta.url), 'utf8');
  assert.match(html, /data-mobile-src="assets\/media\/camera-hal-mobile-v1.mp4"/);
  assert.match(html, /video-variant.js" defer/);
  for (const mobile of [false, true]) {
    const video = {dataset:{mobileSrc:'mobile.mp4',mobilePoster:'mobile.jpg'},preload:'none',src:'desktop.mp4'};
    let mediaQueries = 0;
    const window = {matchMedia:query => {
      assert.match(query, /pointer: coarse/);
      mediaQueries++;
      return {matches:mobile};
    }};
    // No play(), load() or resize listener is provided: the selector must
    // leave playback and orientation changes to the native player.
    vm.runInNewContext(script, {window,document:{querySelector:()=>video}});
    assert.equal(video.src, mobile?'mobile.mp4':'desktop.mp4');
    assert.equal(video.preload, 'none');
    assert.equal(mediaQueries, 1);
    if (mobile) {
      assert.equal(video.dataset.layout, 'portrait');
      assert.equal(video.width/video.height, 9/16);
    }
  }
});
