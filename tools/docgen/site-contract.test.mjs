import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const docs = path.join(root, 'docs');
const config = fs.readFileSync(path.join(root, 'guide/_config.yml'), 'utf8');
const nav = [...config.matchAll(/- path: (\S+)\s+label: ([^\r\n]+)/g)];

test('every published page loads full-text search relative to the site root', () => {
  const manifest = JSON.parse(fs.readFileSync(path.join(docs, '.site-manifest.json'), 'utf8'));
  assert.ok(manifest.files['assets/search.js']);
  assert.ok(manifest.files['assets/search-core.js']);
  for (const target of Object.keys(manifest.files).filter(file => file.endsWith('.html'))) {
    const html = fs.readFileSync(path.join(docs, target), 'utf8');
    const rootPrefix = target.includes('/') ? '../' : './';
    assert.ok(html.includes(`src="${rootPrefix}assets/search.js"`), `${target}: search module`);
    assert.ok(html.includes('id="search-dialog"'), `${target}: search dialog`);
    assert.ok(html.includes('id="search-status" role="status"'), `${target}: accessible status`);
  }
});

test('every navigation target has a matching title and exactly one active tab', () => {
  assert.ok(nav.length > 0);
  for (const [, target, label] of nav) {
    const html = fs.readFileSync(path.join(docs, target), 'utf8');
    assert.ok(html.includes(`<title>${label} ·`), `${target}: browser title must match navigation`);
    assert.equal((html.match(/aria-current="page"/g) || []).length, 1, `${target}: one active tab`);
    const active = html.match(/<a\b[^>]*aria-current="page"[^>]*>([^<]+)<\/a>/);
    assert.equal(active?.[1], label, `${target}: active tab must match content`);
    assert.match(html, /<h1\b[^>]*lang="en"/, `${target}: English editorial heading`);
    assert.doesNotMatch(html, /<h2\b[^>]*lang="en"/, `${target}: Korean section headings`);
  }
});

test('published internal links resolve to existing files and section IDs', () => {
  const htmlFiles = fs.readdirSync(docs, { recursive: true }).filter(f => f.endsWith('.html'));
  for (const relative of htmlFiles) {
    const html = fs.readFileSync(path.join(docs, relative), 'utf8');
    for (const [, href] of html.matchAll(/<a\b[^>]*href="([^"]+)"/g)) {
      const url = new URL(href.replaceAll('&amp;', '&'), `https://docs.invalid/${relative.replaceAll('\\', '/')}`);
      if (url.origin !== 'https://docs.invalid') continue;
      const destination = path.join(docs, decodeURIComponent(url.pathname));
      assert.ok(fs.existsSync(destination), `${relative}: missing target ${href}`);
      if (!url.hash || !destination.endsWith('.html')) continue;
      const target = fs.readFileSync(destination, 'utf8');
      const ids = [...target.matchAll(/\bid="([^"]+)"/g)].map(m => m[1]);
      assert.ok(ids.includes(decodeURIComponent(url.hash.slice(1))), `${relative}: missing anchor ${href}`);
    }
  }
});
