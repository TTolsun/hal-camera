// Keep one semantic table for every viewport; narrow screens read each row as a card.
for (const table of document.querySelectorAll('main table')) {
  const headers = [...(table.tHead?.rows[0]?.cells ?? [])];
  const rows = [...table.tBodies].flatMap(body => [...body.rows]);
  if (headers.length < 3 || !rows.length) continue;
  if ([...headers, ...rows.flatMap(row => [...row.cells])].some(cell => cell.colSpan !== 1 || cell.rowSpan !== 1)
      || rows.some(row => row.cells.length !== headers.length)) continue;
  table.classList.add('responsive-table');
  table.setAttribute('role', 'table');
  table.tHead.setAttribute('role', 'rowgroup');
  table.tHead.rows[0].setAttribute('role', 'row');
  headers.forEach(header => { header.scope = 'col'; header.setAttribute('role', 'columnheader'); });
  for (const body of table.tBodies) body.setAttribute('role', 'rowgroup');
  for (const row of rows) {
    row.setAttribute('role', 'row');
    [...row.cells].forEach((cell, index) => {
      cell.setAttribute('role', 'cell');
      const label = document.createElement('span');
      label.className = 'table-cell-label';
      label.setAttribute('aria-hidden', 'true');
      label.textContent = headers[index].textContent.trim();
      cell.prepend(label);
    });
  }
}
