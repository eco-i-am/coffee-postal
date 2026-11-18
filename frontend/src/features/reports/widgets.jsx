import { useCallback, useState } from "react";
import { api } from "../../shared/api.js";
import { useLoad } from "../../shared/hooks.js";
import { PRESETS, presetRange } from "../../shared/dates.js";
import { todayManila } from "../../shared/format.js";
import { downloadCsv } from "../../shared/csv.js";
import { downloadServerFile } from "../../shared/export.js";

/** Pieces shared by every report screen. */

export function Kpi({ label, value, note, bad }) {
  return <div className="kpi"><small>{label}</small><b className={bad ? "short" : ""}>{value}</b>{note && <span>{note}</span>}</div>;
}

/** Download a report as CSV, Excel or PDF. rows = the header row, then the data rows. */
export function ExportButton({ name, rows, range }) {
  const [busy, setBusy] = useState("");
  const [err, setErr] = useState("");
  const off = !rows || rows.length < 2;

  async function get(format) {
    setErr(""); setBusy(format);
    try { await downloadServerFile(format, { name, rows, range }); }
    catch (e) { setErr(e.message); }
    finally { setBusy(""); }
  }

  return (
    <span className="exports" role="group" aria-label="Download">
      <button className="btn ghost sm" type="button" disabled={off}
        onClick={() => downloadCsv(`${name}${range ? `_${range.from}_to_${range.to}` : ""}.csv`, rows)}>CSV</button>
      <button className="btn ghost sm" type="button" disabled={off || !!busy} onClick={() => get("xlsx")}>{busy === "xlsx" ? "Making..." : "Excel"}</button>
      <button className="btn ghost sm" type="button" disabled={off || !!busy} onClick={() => get("pdf")}>{busy === "pdf" ? "Making..." : "PDF"}</button>
      {err && <small className="err" role="alert"> {err}</small>}
    </span>
  );
}

export function RangeBar({ range, setRange }) {
  const [preset, setPreset] = useState("7d");
  const pick = (k) => { setPreset(k); setRange(presetRange(k, todayManila())); };
  return (
    <div className="filters">
      <div className="tabs" role="group" aria-label="Date range">
        {PRESETS.map(([k, label]) => <button key={k} className="chip" aria-pressed={preset === k} onClick={() => pick(k)}>{label}</button>)}
      </div>
      <label className="datepick">From
        <input type="date" value={range.from} max={range.to} onChange={(e) => { if (e.target.value) { setPreset(""); setRange({ ...range, from: e.target.value }); } }} /></label>
      <label className="datepick">To
        <input type="date" value={range.to} min={range.from} max={todayManila()} onChange={(e) => { if (e.target.value) { setPreset(""); setRange({ ...range, to: e.target.value }); } }} /></label>
    </div>
  );
}

export function useReport(path, range, extra = "") {
  const load = useCallback(() => api(`${path}?from=${range.from}&to=${range.to}${extra}`), [path, range.from, range.to, extra]);
  return useLoad(load);
}

export function State({ r }) {
  if (r.error) return <p className="notice" role="alert">{r.error}</p>;
  if (r.loading && !r.data) return <p role="status">Loading...</p>;
  return null;
}
