// CSV export for reports. Runs in the browser: builds the file and offers it as a download.

/** One cell. Quotes when needed, and defuses text that a spreadsheet would run as a formula (=, +, @). */
export function csvCell(v) {
  let s = v === null || v === undefined ? "" : String(v);
  if (typeof v === "string" && /^[=+@\t\r]/.test(s)) s = `'${s}`;
  if (typeof v === "string" && /^-/.test(s) && Number.isNaN(Number(s))) s = `'${s}`;
  return /[",\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

export function toCsv(rows) {
  return rows.map((r) => r.map(csvCell).join(",")).join("\r\n") + "\r\n";
}

/** Centavos as a plain number for spreadsheets: 12550 -> "125.50". */
export const pesoCell = (c) => (c / 100).toFixed(2);

export function downloadCsv(filename, rows) {
  // the BOM makes Excel read peso names and accents correctly
  const blob = new Blob(["﻿" + toCsv(rows)], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}
