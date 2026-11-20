import { useCallback, useMemo, useState } from "react";
import { api } from "../../shared/api.js";
import { useLoad } from "../../shared/hooks.js";
import { peso } from "../../shared/money.js";
import { PRESETS, presetRange } from "../../shared/dates.js";
import { dateOf, dateTimeOf, todayManila } from "../../shared/format.js";

/** A cashier's own sales and drawer counts. Only ever shows the signed-in person, never anyone else. */
export default function MyReport() {
  const [preset, setPreset] = useState("7d");
  const range = useMemo(() => presetRange(preset, todayManila()), [preset]);
  const load = useCallback(() => api(`/api/branch/my-report?from=${range.from}&to=${range.to}`), [range.from, range.to]);
  const { data: d, error, loading } = useLoad(load);

  return (
    <div className="sec">
      <div className="pghead">
        <h2>My sales</h2>
        <div className="tabs" role="group" aria-label="My report range">
          {PRESETS.slice(0, 4).map(([k, label]) => <button key={k} type="button" className="chip" aria-pressed={preset === k} onClick={() => setPreset(k)}>{label}</button>)}
        </div>
      </div>
      {error && <p className="err" role="alert">{error}</p>}
      {loading && !d && <p role="status">Loading...</p>}
      {d && (
        <>
          <div className="kpis slim">
            <div className="kpi"><small>My sales</small><b>{peso(d.sales)}</b><span>{d.orders} orders · {peso(d.avgOrder)} average</span></div>
            <div className="kpi"><small>Discounts I gave</small><b>{peso(d.discount)}</b></div>
            <div className="kpi"><small>Refunds</small><b className={d.refunds > 0 ? "short" : ""}>{peso(d.refunded)}</b><span>{d.refunds} {d.refunds === 1 ? "order" : "orders"}</span></div>
          </div>
          {d.days.length > 1 && (
            <ColumnChart points={dayPoints(d.days)} name="My sales by day" per="day" what="Date" />
          )}
          <div className="tw">
            <table className="dense">
              <thead><tr><th>Shift opened</th><th>Closed</th><th className="n">Opening</th><th className="n">Expected</th><th className="n">Counted</th><th className="n">Over / short</th></tr></thead>
              <tbody>
                {d.shifts.map((s, i) => {
                  const diff = s.counted != null && s.expected != null ? s.counted - s.expected : null;
                  return (
                    <tr key={i}><td>{dateTimeOf(s.openedAt)}</td><td>{s.closedAt ? dateTimeOf(s.closedAt) : <span className="tag y">Open</span>}</td>
                      <td className="n">{peso(s.opening)}</td><td className="n">{s.expected != null ? peso(s.expected) : ""}</td><td className="n">{s.counted != null ? peso(s.counted) : ""}</td>
                      <td className={`n${diff < 0 ? " short" : ""}`}>{diff == null ? "" : diff === 0 ? "Exact" : `${diff > 0 ? "+" : "-"}${peso(Math.abs(diff))}`}</td></tr>
                  );
                })}
                {d.shifts.length === 0 && <tr><td colSpan="6" className="emptyrow">No shifts in this range.</td></tr>}
              </tbody>
            </table>
          </div>
        </>
      )}
    </div>
  );
}
