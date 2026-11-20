import { useEffect, useState } from "react";
import { api } from "./api.js";

const KEY = "cp.ratelink.v1";

/** The public page a customer opens by scanning the QR code. */
export const rateUrl = (token) => `${window.location.origin}/rate/${token}`;

/** The branch's rating code. Kept on the device, so a receipt still gets its QR when the internet is down. */
export function useRateToken() {
  const [token, setToken] = useState(() => { try { return localStorage.getItem(KEY) || ""; } catch { return ""; } });
  useEffect(() => {
    let alive = true;
    api("/api/branch/feedback/link").then((r) => {
      try { localStorage.setItem(KEY, r.token); } catch { /* ignore */ }
      if (alive) setToken(r.token);
    }).catch(() => {});
    return () => { alive = false; };
  }, []);
  return token;
}

/** A QR code drawn as SVG on this device (no outside service), so it also works offline. */
export default function QrCode({ text, size = 120, label = "QR code" }) {
  const [svg, setSvg] = useState("");
  useEffect(() => {
    let alive = true;
    import("qrcode").then((m) => (m.default || m).toString(text, { type: "svg", margin: 0, errorCorrectionLevel: "M" }))
      .then((s) => { if (alive) setSvg(s); }).catch(() => {});
    return () => { alive = false; };
  }, [text]);
  if (!svg) return null;
  return <div className="qr" role="img" aria-label={label} style={{ width: size, height: size }} dangerouslySetInnerHTML={{ __html: svg }} />;
}
