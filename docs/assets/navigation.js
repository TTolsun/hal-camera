// Enhance the generated links; without JavaScript every destination stays reachable.
(() => {
  const nav = document.querySelector('.site-nav');
  if (!nav) return;
  const groups = [
    { label: '검증 도구', pages: ['probe.html', 'cts.html', 'benchmark.html', 'callback.html'] },
    { label: '도움말', pages: ['automation.html', 'troubleshooting.html'] },
  ];
  const links = [...nav.querySelectorAll('a')];
  const menus = [];
  for (const group of groups) {
    const items = group.pages.map(page => links.find(link =>
      new URL(link.href).pathname.split('/').pop() === page));
    if (items.some(link => !link)) continue;
    const menu = document.createElement('details');
    menu.className = 'nav-group';
    const summary = document.createElement('summary');
    summary.textContent = group.label;
    const panel = document.createElement('div');
    panel.className = 'nav-panel';
    if (items.some(link => link.getAttribute('aria-current') === 'page')) {
      menu.classList.add('has-current');
    }
    items[0].before(menu);
    panel.append(...items);
    menu.append(summary, panel);
    menus.push(menu);
    menu.addEventListener('toggle', () => {
      if (menu.open) menus.forEach(other => { if (other !== menu) other.open = false; });
    });
  }
  nav.classList.add('nav-grouped');
  document.addEventListener('click', event => {
    menus.forEach(menu => { if (!menu.contains(event.target)) menu.open = false; });
  });
  document.addEventListener('focusin', event => {
    menus.forEach(menu => { if (!menu.contains(event.target)) menu.open = false; });
  });
  nav.addEventListener('keydown', event => {
    if (event.key !== 'Escape') return;
    const menu = menus.find(item => item.open);
    if (!menu) return;
    event.preventDefault();
    menu.open = false;
    menu.querySelector('summary').focus();
  });
})();
