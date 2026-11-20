import { useLayoutEffect, useState } from "react";

/**
 * Where to put a card beside a highlighted box: below it, else above, else to the side, else along the bottom of the screen.
 * Returns {top,left}, or null when there is no box (the caller then centres the card itself).
 */
export function useCardPosition(cardRef, rect) {
  const [pos, setPos] = useState(null);
  useLayoutEffect(() => {
    const c = cardRef.current;
    if (!c || !rect) { setPos((old) => (old === null ? old : null)); return; }
    const vw = window.innerWidth, vh = window.innerHeight, w = c.offsetWidth, h = c.offsetHeight, gap = 16, m = 12;
    const clampX = (x) => Math.max(m, Math.min(x, vw - w - m));
    const clampY = (y) => Math.max(m, Math.min(y, vh - h - m));
    let top, left;
    if (vh - (rect.top + rect.height) - gap - m >= h) { top = rect.top + rect.height + gap; left = clampX(rect.left); }
    else if (rect.top - gap - m >= h) { top = rect.top - gap - h; left = clampX(rect.left); }
    else if (vw - (rect.left + rect.width) - gap - m >= w) { left = rect.left + rect.width + gap; top = clampY(rect.top); }
    else if (rect.left - gap - m >= w) { left = rect.left - gap - w; top = clampY(rect.top); }
    else { top = vh - h - m; left = clampX((vw - w) / 2); }
    setPos((old) => (old && old.top === top && old.left === left ? old : { top, left }));
  });
  return pos;
}
