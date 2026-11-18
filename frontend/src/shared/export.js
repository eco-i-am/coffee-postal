// Downloads a report table as Excel or PDF. The server builds the file from the same rows the screen shows (and CSV uses).
import { ApiError } from "./api.js";

const TYPES = { xlsx: "xlsx", pdf: "pdf" };

function xsrf() {
  const m = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]+)/);
  return m ? decodeURIComponent(m[1]) : "";
}

/** "profit_loss" -> "Profit loss": a readable title for the file's first line. */
export function titleOf(name) {
  const s = String(name || "report").replace(/[_-]+/g, " ").trim();
  return s.charAt(0).toUpperCase() + s.slice(1);
}

export function exportFilename(name, range, format) {
  return `${name}${range ? `_${range.from}_to_${range.to}` : ""}.${format}`;
}

export async function downloadServerFile(format, { name, rows, range }) {
  if (!TYPES[format]) throw new Error("Unknown format");
  let res;
  try {
    res = await fetch(`/api/export/${format}`, {
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": xsrf() },
      body: JSON.stringify({ title: titleOf(name), subtitle: range ? `${range.from} to ${range.to}` : "", rows }),
    });
  } catch {
    throw new ApiError(0, "Could not reach the server. Check the connection and try again.");
  }
  if (!res.ok) {
    let message = "Could not make the file. Try again.";
    try { message = (await res.json()).message || message; } catch { /* keep the default */ }
    throw new ApiError(res.status, message);
  }
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = exportFilename(name, range, format);
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
