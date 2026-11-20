import { useCallback, useEffect, useState } from "react";
import { api } from "./api.js";

// Photos for menu products and inventory items. The server says which ones exist (and a version for each);
// the picture itself is a normal image URL that the browser and the offline cache can keep.

const KEY = "cp-images-v1";
export const MAX_SIDE = 480;      // pixels; a tile and a table thumbnail never need more
export const MAX_BYTES = 380_000; // the server accepts up to 400 KB

function saved() {
  try { return JSON.parse(localStorage.getItem(KEY) || "[]"); } catch { return []; }
}

export const imageUrl = (type, id, ver) => `/api/pos/images/${type}/${id}?v=${ver}`;

/** Which products/items have a photo. Falls back to the last list this device saw when there is no internet. */
export function useImages() {
  const [list, setList] = useState(saved);
  const reload = useCallback(async () => {
    try {
      const l = await api("/api/pos/images", { timeoutMs: 8000 });
      setList(l);
      try { localStorage.setItem(KEY, JSON.stringify(l)); } catch { /* storage full or blocked: carry on */ }
    } catch { /* offline: keep what we have */ }
  }, []);
  useEffect(() => { reload(); }, [reload]);
  const url = useCallback((type, id) => {
    const e = list.find((x) => x.type === type && x.id === id);
    return e ? imageUrl(type, id, e.ver) : null;
  }, [list]);
  return { url, reload };
}

/** Largest size that fits in max x max, keeping the shape. */
export function fitSize(w, h, max = MAX_SIDE) {
  const s = Math.min(1, max / Math.max(w, h));
  return { w: Math.max(1, Math.round(w * s)), h: Math.max(1, Math.round(h * s)) };
}

/** Square centre crop (what a tile shows), as source x, y and side. */
export function squareCrop(w, h) {
  const side = Math.min(w, h);
  return { x: Math.round((w - side) / 2), y: Math.round((h - side) / 2), side };
}

/** Shrink and crop a chosen photo to a small square JPEG, so phone photos of several MB become tens of KB. */
export async function shrink(file) {
  const bmp = await createImageBitmap(file);
  const { x, y, side } = squareCrop(bmp.width, bmp.height);
  const { w } = fitSize(side, side);
  const canvas = document.createElement("canvas");
  canvas.width = w; canvas.height = w;
  const ctx = canvas.getContext("2d");
  ctx.fillStyle = "#fff";   // a transparent PNG would otherwise turn black as a JPEG
  ctx.fillRect(0, 0, w, w);
  ctx.drawImage(bmp, x, y, side, side, 0, 0, w, w);
  bmp.close?.();
  for (const q of [0.85, 0.75, 0.62, 0.5]) {
    const blob = await new Promise((r) => canvas.toBlob(r, "image/jpeg", q));
    if (blob && blob.size <= MAX_BYTES) return blob;
  }
  throw new Error("That photo is too detailed to shrink. Try another one.");
}
