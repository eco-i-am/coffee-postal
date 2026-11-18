import { useCallback, useState } from "react";
import { api } from "../../shared/api.js";
import { useLoad } from "../../shared/hooks.js";
import { PRESETS, presetRange } from "../../shared/dates.js";
import { todayManila } from "../../shared/format.js";
import { downloadCsv } from "../../shared/csv.js";

/** Pieces shared by every report screen. */

export function Kpi({ label, value, note, bad }) {
  return <div className="kpi"><small>{label}</small><b className={bad ? "short" : ""}>{value}</b>{note && <span>{note}</span>}</div>;
}

export function ExportButton({ name, rows, range }) {
  return (
    <button className="btn ghost sm" type="button" disabled={!rows || rows.length < 2}
      onClick={() => downloadCsv(`${name}${range ? `_${range.from}_to_${range.to}` : ""}.csv`, rows)}>
      Download CSV
    </button>
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
