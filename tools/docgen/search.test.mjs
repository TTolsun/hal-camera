import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import { search, extractSections } from '../../guide/assets/search-core.js';

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
  assert.ok(result.snippet.length <= 132);
});

test('prefers a complete body phrase over scattered matches and keeps it in the excerpt', () => {
  const scattered = { ...sections[0], heading: '도착 시각', text: '시각 설명입니다. '.repeat(30) + '센서 데이터', url: '/scattered' };
  const exact = { ...sections[0], text: '설명입니다. '.repeat(30) + '센서 시각을 비교합니다.', url: '/exact' };
  const [result] = search([scattered, exact], '센서 시각');
  assert.equal(result.url, '/exact');
  assert.ok(result.snippet.includes('센서 시각'));
});

test('closed details have direct search links and do not absorb the following section text', () => {
  // The extractor only needs this small read-only DOM surface.
  function element(tagName, id, ...children) {
    const childNodes = children.map(child => typeof child === 'string' ? { nodeType: 3, textContent: child } : child);
    return {
      nodeType: 1, tagName, id, childNodes,
      get textContent() { return childNodes.map(child => child.textContent).join(' '); },
      matches(selector) { return selector === 'details[data-search-section][id]' && tagName === 'DETAILS' && Boolean(id); },
      querySelector(selector) { return selector === 'summary' ? childNodes.find(child => child.tagName === 'SUMMARY') : null; },
    };
  }
  const main = element('MAIN', '', element('H2', 'parent', '상위 설명'), '펼치기 전 설명',
    element('DETAILS', 'closed', element('SUMMARY', '', '상세 설명'), 'SkipDiagnosis',
      element('DETAILS', 'nested', element('SUMMARY', '', '안쪽 설명'), 'nestedOnly')),
    '다시 상위 설명', element('H2', 'next', '다음 절'), '다음 내용');
  const result = extractSections({ title: 'CTS · Guide', querySelector: () => main }, '/cts.html');
  assert.equal(search(result, 'SkipDiagnosis')[0].url, '/cts.html#closed');
  assert.equal(search(result, 'nestedOnly')[0].url, '/cts.html#nested');
  assert.equal(search(result, '다시 상위')[0].url, '/cts.html#parent');
  assert.equal(search(result, '다음 내용')[0].url, '/cts.html#next');
  assert.equal(search(result, 'SkipDiagnosis 다시').length, 0);
  assert.equal(search(result, '상위 설명').filter(section => section.url === '/cts.html#parent').length, 1);
  assert.equal(search(result, '펼치기 다시')[0].url, '/cts.html#parent');
});

test('diagram syntax stays out of excerpts while prose and command examples remain searchable', () => {
  const element = (tagName, text, mermaid = false) => ({
    nodeType: 1, tagName, id: '', textContent: text,
    childNodes: [{ nodeType: 3, textContent: text }],
    matches: selector => mermaid && selector.includes('code.language-mermaid'),
  });
  const main = {
    nodeType: 1, tagName: 'MAIN', matches: () => false,
    childNodes: [
      element('CODE', 'sequenceDiagram A->>B: 저장 alt 성공 end', true),
      element('P', 'IS_PENDING으로 공개를 미룹니다.'),
      element('CODE', 'halcam fetch UUID'),
    ],
  };
  const sections = extractSections({ title: 'Engine · Guide', querySelector: () => main }, '/engine.html');
  assert.equal(search(sections, 'sequenceDiagram').length, 0);
  assert.equal(search(sections, 'IS_PENDING')[0].snippet, 'IS_PENDING으로 공개를 미룹니다. halcam fetch UUID');
  assert.equal(search(sections, 'halcam fetch')[0].url, '/engine.html');
});

test('late diagrams realign a linked disclosure without overriding manual scrolling', () => {
  const source = fs.readFileSync(new URL('../../guide/assets/section-links.js', import.meta.url), 'utf8');
  function fixture() {
    const events = new Map(), scrolls = [], detail = { open: false, parentElement: null };
    const target = { closest: () => detail, querySelector: () => null, scrollIntoView: options => scrolls.push(options) };
    const location = { hash: '#saved' };
    vm.runInNewContext(source, {
      location,
      document: { getElementById: id => id === 'saved' ? target : null, addEventListener() {} },
      window: { addEventListener: (name, callback) => events.set(name, callback) },
      requestAnimationFrame: callback => callback(),
    });
    return { events, scrolls, detail, location };
  }
  const linked = fixture();
  assert.equal(linked.detail.open, true);
  linked.events.get('halcamera:diagrams-ready')();
  assert.equal(linked.scrolls.length, 2);
  assert.equal(linked.scrolls[1].behavior, 'instant');
  const reading = fixture();
  reading.events.get('wheel')();
  reading.events.get('halcamera:diagrams-ready')();
  assert.equal(reading.scrolls.length, 1);
  const malformed = fixture();
  malformed.location.hash = '#%broken';
  assert.doesNotThrow(() => malformed.events.get('halcamera:diagrams-ready')());
  assert.equal(malformed.scrolls.length, 1);
});
