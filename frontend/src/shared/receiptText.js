import { useEffect, useState } from "react";
import { api } from "./api.js";

const KEY = "cp.receipt.v1";
const DEFAULT = { companyName: "COFFEE POSTAL", footer: "Thank you! Your affordable freshly brewed coffee destination.", note: "Not an official receipt." };

function cached() {
  try { const s = JSON.parse(localStorage.getItem(KEY) || "null"); if (s && s.footer) return s; } catch { /* ignore */ }
  return null;
}

/**
 * The receipt wording HQ set in Settings. The last answer is kept on the device, so a receipt still prints with the right words offline,
 * and the built-in wording is the fallback before HQ has changed anything.
 */
export function useReceiptText() {
  const [t, setT] = useState(() => cached() || DEFAULT);
  useEffect(() => {
    let alive = true;
    api("/api/settings/receipt").then((r) => {
      const next = { companyName: (r.companyName || "").toUpperCase() || DEFAULT.companyName, footer: r.footer ?? DEFAULT.footer, note: r.note ?? DEFAULT.note };
      try { localStorage.setItem(KEY, JSON.stringify(next)); } catch { /* ignore */ }
      if (alive) setT(next);
    }).catch(() => {});
    return () => { alive = false; };
  }, []);
  return t;
}
