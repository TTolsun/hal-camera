import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

test('home video waits for explicit playback without eagerly fetching media', () => {
  const html = fs.readFileSync(new URL('../../docs/index.html', import.meta.url), 'utf8');
  const video = html.match(/<video\b[^>]*>/)[0];
  assert.match(video, /\bcontrols\b/);
  assert.match(video, /preload="none"/);
  assert.doesNotMatch(video, /\bautoplay\b/);
  assert.doesNotMatch(html, /brand-film.*\.js/);
});
