import test from 'node:test';
import assert from 'node:assert/strict';
import { search } from '../../guide/assets/search-core.js';

const sections = [
  { title: 'Engine', heading: '카메라 동작', text: '센서 시각은 SENSOR_INFO_TIMESTAMP_SOURCE가 REALTIME일 때 비교합니다. close(done) 뒤에 엽니다.', url: '/engine.html#timing' },
  { title: 'Architecture', heading: '센서 시각', text: '시각 도메인을 유지합니다.', url: '/architecture.html#clock' },
];
test('finds body-only Korean phrases and case-insensitive API names', () => {
  assert.equal(search(sections, 'REALTIME')[0].url, '/engine.html#timing');
  assert.equal(search(sections, 'sensor_info_timestamp_source')[0].url, '/engine.html#timing');
  assert.equal(search(sections, '비교합니다'.normalize('NFD')).length, 1);
});
test('matches every term and ranks headings ahead of body matches', () => {
  assert.equal(search(sections, '센서 시각')[0].title, 'Architecture');
  assert.equal(search(sections, 'Engine REALTIME')[0].title, 'Engine');
  assert.equal(search(sections, 'REALTIME missing').length, 0);
  assert.equal(search(sections, '   ').length, 0);
  assert.equal(search(sections, '<img onerror=alert(1)>').length, 0);
  assert.equal(search(sections, 'close(done)').length, 1);
});
test('shows the matching body passage even near the end of a long section', () => {
  const text = '앞부분의 설명입니다. '.repeat(100) + '희귀검색어가 있는 마지막 문장입니다.';
  const [result] = search([{ ...sections[0], text }], '희귀검색어');
  assert.ok(result.snippet.includes('희귀검색어'));
  assert.ok(result.snippet.startsWith('…'));
  assert.ok(result.snippet.length <= 222);
});
