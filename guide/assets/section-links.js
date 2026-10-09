// Open linked disclosures for search results, shared URLs and same-page links.
function reveal(hash = location.hash) {
  if (!hash) return;
  let id;
  try { id = decodeURIComponent(hash.slice(1)); } catch { return; }
  const target = document.getElementById(id);
  if (!target) return;
  let detail = target.closest('details');
  let opened = false;
  while (detail) {
    if (!detail.open) { detail.open = true; opened = true; }
    detail = detail.parentElement?.closest('details');
  }
  // Legacy screenshot links point at the wrapper outside the disclosure.
  const child = target.querySelector(':scope > details');
  if (child && !child.open) { child.open = true; opened = true; }
  if (opened) requestAnimationFrame(() => target.scrollIntoView({ block: 'start' }));
}

reveal();
window.addEventListener('hashchange', () => reveal());
document.addEventListener('click', event => {
  if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
  const link = event.target.closest('a[href]');
  if (!link) return;
  const url = new URL(link.href, location.href);
  if (url.origin === location.origin && url.pathname === location.pathname && url.search === location.search) reveal(url.hash);
});
