// Shared by the browser UI and the search regression tests. No external service.
export const normalize = value => value.normalize('NFC').toLowerCase();

export function search(sections, query) {
  const phrase = normalize(query).trim().replace(/\s+/g, ' ');
  const terms = [...new Set(phrase.split(/\s+/).filter(Boolean))];
  if (!terms.length) return [];
  return sections.map(section => {
    const title = normalize(section.title);
    const heading = normalize(section.heading);
    const body = normalize(section.text);
    if (!terms.every(term => `${title} ${heading} ${body}`.includes(term))) return null;
    const score = (heading.includes(phrase) ? 40 : 0) + (title.includes(phrase) ? 24 : 0) + (body.includes(phrase) ? 20 : 0)
      + terms.reduce((sum, term) => sum + (heading.includes(term) ? 8 : 0)
      + (title.includes(term) ? 4 : 0) + (body.includes(term) ? 1 : 0), 0);
    const positions = terms.map(term => body.indexOf(term)).filter(index => index >= 0);
    const position = body.includes(phrase) ? body.indexOf(phrase) : (positions.length ? Math.min(...positions) : 0);
    let start = Math.max(0, position - 32);
    const nextSpace = body.indexOf(' ', start);
    if (start && nextSpace >= start && nextSpace < position) start = nextSpace + 1;
    const length = Math.max(130, phrase.length + 64);
    const limit = Math.min(section.text.length, start + length);
    const boundary = section.text.lastIndexOf(' ', limit);
    const end = limit < section.text.length && boundary > position + phrase.length ? boundary : limit;
    const excerpt = section.text.slice(start, end);
    return { ...section, score, snippet: `${start ? '…' : ''}${excerpt}${end < section.text.length ? '…' : ''}` };
  }).filter(Boolean).sort((a, b) => b.score - a.score || a.url.localeCompare(b.url));
}

export function extractSections(doc, url) {
  const main = doc.querySelector('main');
  if (!main) throw new Error(`Document has no main content: ${url}`);
  const title = doc.title.split(' · ')[0].trim();
  const sections = [];
  let current = { title, heading: title, url, text: '' };
  let hasHeading = false;
  function visit(node) {
    if (node.nodeType === 3) { current.text += `${node.textContent} `; return; }
    // Search the explanation around a diagram, not Mermaid's rendering syntax.
    if (node.nodeType !== 1 || node.matches('script, style, noscript, nav, button, code.language-mermaid, [hidden], [aria-hidden="true"]')) return;
    if (node.matches('details[data-search-section][id]')) {
      // A closed disclosure is searchable and has its own deep link.
      const parent = { ...current, text: '' };
      if (current.text.trim()) sections.push(current);
      const summary = node.querySelector('summary');
      current = { title, heading: summary?.textContent.trim() || parent.heading,
        url: `${url}#${encodeURIComponent(node.id)}`, text: '' };
      node.childNodes.forEach(child => { if (child !== summary) visit(child); });
      if (current.text.trim()) sections.push(current);
      current = parent;
      return;
    }
    if (/^H[1-6]$/.test(node.tagName)) {
      if (hasHeading || current.text.trim()) sections.push(current);
      current = { title, heading: node.textContent.trim(), url: node.id ? `${url}#${encodeURIComponent(node.id)}` : url, text: '' };
      hasHeading = true;
      return;
    }
    node.childNodes.forEach(visit);
  }
  visit(main);
  sections.push(current);
  // Disclosures split an enclosing section into pieces. Merge those pieces so
  // each destination appears once and terms on either side remain searchable.
  const merged = new Map();
  for (const section of sections) {
    const existing = merged.get(section.url);
    if (existing) existing.text += ` ${section.text}`;
    else merged.set(section.url, { ...section });
  }
  return [...merged.values()].map(section => ({ ...section, heading: section.heading.normalize('NFC'), text: section.text.normalize('NFC').replace(/\s+/g, ' ').trim() }));
}
