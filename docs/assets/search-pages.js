// Contributor references stay available by URL, outside the reader's search flow.
const contributorPages = new Set(['architecture.html', 'building.html', 'contributing.html', 'decisions.html']);
export const isReaderPage = path => path.endsWith('.html') && !path.startsWith('_inputs/') && !contributorPages.has(path);
