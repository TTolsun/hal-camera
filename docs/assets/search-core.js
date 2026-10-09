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
    const score = (heading.includes(phrase) ? 12 : 0) + (body.includes(phrase) ? 4 : 0)
      + terms.reduce((sum, term) => sum + (heading.includes(term) ? 8 : 0)
      + (title.includes(term) ? 4 : 0) + (body.includes(term) ? 1 : 0), 0);
    const positions = terms.map(term => body.indexOf(term)).filter(index => index >= 0);
    const position = body.includes(phrase) ? body.indexOf(phrase) : (positions.length ? Math.min(...positions) : 0);
    const start = Math.max(0, position - 65);
    const excerpt = section.text.slice(start, start + 220);
    return { ...section, score, snippet: `${start ? '…' : ''}${excerpt}${start + 220 < section.text.length ? '…' : ''}` };
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
    if (node.nodeType !== 1 || node.matches('script, style, noscript, nav, button, [hidden], [aria-hidden="true"]')) return;
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
  return sections.map(section => ({ ...section, heading: section.heading.normalize('NFC'), text: section.text.normalize('NFC').replace(/\s+/g, ' ').trim() }));
}
