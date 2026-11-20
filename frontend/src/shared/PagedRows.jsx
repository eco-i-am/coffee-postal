import { useState } from "react";

export const PAGE_SIZE = 25;

/** The numbers of the page buttons to show: first, last, and the pages around the current one, with gaps as null. */
export function pageList(page, pages) {
  const keep = new Set([0, pages - 1, page - 1, page, page + 1]);
  const out = [];
  let last = -1;
  for (let i = 0; i < pages; i++) {
    if (!keep.has(i)) continue;
    if (i - last > 1) out.push(null);
    out.push(i);
    last = i;
  }
  return out;
}

/**
 * The rows of a table, one page at a time. Put it where the rows go inside <tbody>; render(row, index) returns one <tr>.
 * A page bar sits in the last row of the table, and shows only when there is more than one page. Going back to the first
 * page happens by itself when the list gets shorter (a search or a filter).
 */
export default function PagedRows({ rows, render, size = PAGE_SIZE }) {
  const [page, setPage] = useState(0);
  const total = rows.length;
  const pages = Math.max(1, Math.ceil(total / size));
  const at = Math.min(page, pages - 1);
  const from = at * size;
  const slice = rows.slice(from, from + size);

  return (
    <>
      {slice.map((r, i) => render(r, from + i))}
      {pages > 1 && (
        <tr className="pagerrow">
          <td ref={(el) => { if (el) el.colSpan = el.closest("table")?.tHead?.rows[0]?.cells.length || 1; }}>
            <nav className="pager" aria-label="Pages">
              <span className="pagerinfo">{from + 1}-{Math.min(total, from + size)} of {total}</span>
              <button type="button" className="btn ghost sm" disabled={at === 0} onClick={() => setPage(at - 1)}>Previous</button>
              {pageList(at, pages).map((n, k) => n === null
                ? <span key={`gap${k}`} className="pagergap" aria-hidden="true">...</span>
                : <button key={n} type="button" className="btn ghost sm pagenum" aria-current={n === at ? "page" : undefined} aria-label={`Page ${n + 1}`} onClick={() => setPage(n)}>{n + 1}</button>)}
              <button type="button" className="btn ghost sm" disabled={at === pages - 1} onClick={() => setPage(at + 1)}>Next</button>
            </nav>
          </td>
        </tr>
      )}
    </>
  );
}
