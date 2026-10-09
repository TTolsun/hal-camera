import { extractSections, normalize, search } from './search-core.js';

const root = new URL('../', import.meta.url);
const dialog = document.querySelector('#search-dialog');
const openButton = document.querySelector('.search-open');
const input = dialog.querySelector('input');
const status = dialog.querySelector('#search-status');
const results = dialog.querySelector('#search-results');
const retry = dialog.querySelector('.search-retry');
const pages = new Map();
let sections = [];
let loading;
let ready = false;
let failure = '';
let returnFocus;

function highlight(element, value, terms) {
  // Use text nodes, including for queries containing HTML or regexp syntax.
  const folded = normalize(value);
  let cursor = 0;
  while (cursor < value.length) {
    const matches = terms.map(term => ({ at: folded.indexOf(term, cursor), term }))
      .filter(match => match.at >= 0).sort((a, b) => a.at - b.at || b.term.length - a.term.length);
    if (!matches.length) { element.append(value.slice(cursor)); break; }
    const { at, term } = matches[0];
    element.append(value.slice(cursor, at));
    const mark = document.createElement('mark');
    mark.textContent = value.slice(at, at + term.length);
    element.append(mark);
    cursor = at + term.length;
  }
}

function render() {
  results.replaceChildren();
  retry.hidden = !failure;
  if (loading) { status.textContent = '문서 본문을 불러오고 있습니다…'; return; }
  const query = input.value.trim();
  const notice = failure ? `${failure} ` : '';
  if (!ready) { status.textContent = notice; return; }
  if (!query) { status.textContent = `${notice}${pages.size}개 문서의 제목·본문·표·코드 예제를 검색할 수 있습니다.`; return; }
  const matches = search(sections, query);
  const visible = matches.slice(0, 50);
  status.textContent = `${notice}${matches.length ? `${matches.length}개 절을 찾았습니다.${matches.length > 50 ? ' 상위 50개를 표시합니다.' : ''}` : '검색 결과가 없습니다. 다른 검색어를 입력해 보세요.'}`;
  const terms = normalize(query).split(/\s+/).filter(Boolean);
  for (const match of visible) {
    const item = document.createElement('li');
    const link = document.createElement('a');
    link.href = match.url;
    link.addEventListener('click', () => dialog.close());
    const title = document.createElement('span');
    title.className = 'search-page';
    highlight(title, match.title, terms);
    const heading = document.createElement('strong');
    highlight(heading, match.heading, terms);
    const snippet = document.createElement('p');
    highlight(snippet, match.snippet, terms);
    link.append(title, heading, snippet);
    item.append(link);
    results.append(item);
  }
}

async function get(url, type) {
  const response = await fetch(url, { signal: AbortSignal.timeout(15000) });
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return response[type]();
}

async function load() {
  if (loading || (ready && !failure)) return;
  failure = '';
  loading = true;
  render();
  try {
    // The builder owns this list, so newly published pages need no manual registration.
    const manifest = await get(new URL('.site-manifest.json', root), 'json');
    const paths = Object.keys(manifest.files).filter(path => path.endsWith('.html'));
    let failed = 0;
    // Four workers bound concurrency; successful pages are retained when retrying.
    const pending = paths.filter(path => !pages.has(path));
    await Promise.all(Array.from({ length: Math.min(4, pending.length) }, async () => {
      while (pending.length) {
        const path = pending.shift();
        try {
          const url = new URL(path, root);
          if (!url.href.startsWith(root.href)) throw new Error('Invalid document path');
          const html = await get(url, 'text');
          pages.set(path, extractSections(new DOMParser().parseFromString(html, 'text/html'), url.href));
        } catch { failed++; }
      }
    }));
    sections = [...pages.values()].flat();
    ready = pages.size > 0;
    if (failed) failure = `${failed}개 문서를 불러오지 못했습니다. 검색 결과가 일부 누락될 수 있습니다.`;
  } catch {
    failure = '문서를 불러오지 못했습니다. 연결을 확인한 뒤 다시 시도해 주세요.';
  } finally {
    loading = false;
    render();
  }
}

function open() {
  if (!dialog.open) {
    returnFocus = document.activeElement;
    dialog.showModal();
  }
  input.focus();
  input.select();
  load();
}
openButton.hidden = false;
openButton.querySelector('kbd').textContent = /Mac|iPhone|iPad/.test(navigator.platform) ? '⌘ K' : 'Ctrl K';
openButton.addEventListener('click', open);
dialog.querySelector('.search-close').addEventListener('click', () => dialog.close());
dialog.addEventListener('close', () => returnFocus?.focus());
retry.addEventListener('click', load);
input.addEventListener('input', event => { if (!event.isComposing) render(); });
input.addEventListener('compositionend', render);
document.addEventListener('keydown', event => {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k'
      && !document.querySelector('dialog[open]:not(#search-dialog)')) {
    event.preventDefault();
    open();
  }
});
dialog.addEventListener('keydown', event => {
  if (event.isComposing) return;
  if (event.key === 'Escape') {
    event.preventDefault();
    dialog.close();
    return;
  }
  const links = [...results.querySelectorAll('a')];
  const index = links.indexOf(document.activeElement);
  if (event.key === 'ArrowDown' && (document.activeElement === input || index >= 0) && links.length) {
    event.preventDefault();
    links[Math.min(index + 1, links.length - 1)].focus();
  } else if (event.key === 'ArrowUp' && index >= 0) {
    event.preventDefault();
    (index === 0 ? input : links[index - 1]).focus();
  } else if (event.key === 'Enter' && document.activeElement === input && links.length) {
    event.preventDefault();
    links[0].click();
  }
});
