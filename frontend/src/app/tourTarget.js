// Finds the real on-screen element for a tour button entry, so the guided tour can spotlight it.
// An entry is { label, does, sel? }. If `sel` is given, that CSS selector wins. Otherwise the label is matched against the
// visible text of buttons and links: "A / B" and "A, B" mean several buttons, and a leading "+ " is ignored.

const norm = (s) => (s || "").replace(/\s+/g, " ").replace(/^\+\s*/, "").trim().toLowerCase();

/** The button names inside one label: "Fix in / Fix out" -> ["fix in", "fix out"]. */
export function alternatives(label) {
  return label.split(/\s*\/\s*|,\s+/).map(norm).filter(Boolean);
}

const shown = (el) => el.getClientRects().length > 0 && !el.closest("[data-tour-ui]");

/** Every element the entry points at (first match per name), or [] when it is not on this screen. */
export function locate(entry, root = document) {
  if (!root) return [];
  if (entry.sel) {
    const el = [...root.querySelectorAll(entry.sel)].find(shown);
    return el ? [el] : [];
  }
  const all = [...root.querySelectorAll("button, a, summary, [role='tab'], [role='button']")].filter(shown);
  const text = (el) => norm(el.textContent) || norm(el.getAttribute("aria-label"));
  const out = [];
  for (const alt of alternatives(entry.label)) {
    const hit = all.find((el) => text(el) === alt) || all.find((el) => text(el).startsWith(alt));
    if (hit && !out.includes(hit)) out.push(hit);
  }
  return out;
}

/** One box around all the elements, as {top,left,width,height}. Far-apart matches fall back to the first one. */
export function boxOf(els) {
  const rs = els.map((e) => e.getBoundingClientRect()).filter((r) => r.width > 0 && r.height > 0);
  if (rs.length === 0) return null;
  const top = Math.min(...rs.map((r) => r.top)), left = Math.min(...rs.map((r) => r.left));
  const bottom = Math.max(...rs.map((r) => r.bottom)), right = Math.max(...rs.map((r) => r.right));
  const spread = bottom - top > 140 && rs.length > 1;
  const r = spread ? rs[0] : { top, left, bottom, right, width: right - left, height: bottom - top };
  return { top: r.top, left: r.left, width: r.width, height: r.height };
}
