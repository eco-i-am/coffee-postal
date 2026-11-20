import { useState } from "react";
import { peso } from "./money.js";
import { axisLabel, labelEvery, niceTicks } from "./chartScale.js";

/**
 * Sales as columns: a ₱ scale on the left, one thin bar per day or hour, the highest one called out, and the exact figure on hover or
 * focus. A table view lists the same numbers. One series, so one colour and no legend.
 *
 * points: [{ key, label, title, value (centavos), note? }]
 *   label = the short text under the bar ("5", "9a"); title = the full name in the tooltip and table ("Wed, Nov 5").
 * per: what one bar is, for the average line ("day", "hour"). what: the column heading in the table ("Date", "Hour").
 */
export default function ColumnChart({ points, name, per = "day", what = "Date", noteHead = "Orders" }) {
  const [at, setAt] = useState(-1);
  const [view, setView] = useState("chart");
  const n = points.length;
  const peakValue = Math.max(0, ...points.map((p) => p.value));
  if (n === 0 || peakValue === 0) return <p className="hint">No sales in this range.</p>;

  const { ticks, top } = niceTicks(peakValue);
  const peakIdx = points.findIndex((p) => p.value === peakValue);
  const total = points.reduce((s, p) => s + p.value, 0);
  const every = labelEvery(n);
  const focusable = n <= 62;
  const shown = at >= 0 ? points[at] : null;
  const edge = at < 0 ? "" : at / n < 0.18 ? " left" : at / n > 0.82 ? " right" : "";

  return (
    <div className="vchart">
      <div className="vhead">
        <p className="vfacts">
          <span>Highest <b>{peso(peakValue)}</b> <small>{points[peakIdx].title}</small></span>
          <span>Average <b>{peso(Math.round(total / n))}</b> <small>per {per}</small></span>
        </p>
        <div className="vtabs" role="group" aria-label="View">
          <button type="button" className="chip" aria-pressed={view === "chart"} onClick={() => setView("chart")}>Chart</button>
          <button type="button" className="chip" aria-pressed={view === "table"} onClick={() => setView("table")}>Table</button>
        </div>
      </div>

      {view === "chart" ? (
        <div className="vbody" role="img" aria-label={`${name}. Highest ${peso(peakValue)} on ${points[peakIdx].title}. Use the Table view for every value.`}>
          <div className="vy" aria-hidden="true">
            {ticks.map((t) => <span key={t} style={{ bottom: `${(t / top) * 100}%` }}>{axisLabel(t, top)}</span>)}
          </div>
          <div className="vplot" onPointerLeave={() => setAt(-1)}>
            {ticks.map((t) => <i key={t} className="vgrid" style={{ bottom: `${(t / top) * 100}%` }} aria-hidden="true" />)}
            <div className="vcols">
              {points.map((p, i) => (
                <div key={p.key} className={`vcol${i === at ? " on" : ""}`} tabIndex={focusable ? 0 : undefined}
                  aria-label={`${p.title}: ${peso(p.value)}${p.note ? `, ${p.note}` : ""}`}
                  onPointerEnter={() => setAt(i)} onFocus={() => setAt(i)} onBlur={() => setAt(-1)}>
                  <b className="vbar" style={{ height: `${(p.value / top) * 100}%` }}>
                    {i === peakIdx && <em className="vpeak">{axisLabel(p.value, top)}</em>}
                  </b>
                  {i % every === 0 && <span className="vx">{p.label}</span>}
                </div>
              ))}
            </div>
            {shown && (
              <div className={`vtip${edge}`} style={{ left: `${((at + 0.5) / n) * 100}%` }} role="status">
                <b>{peso(shown.value)}</b>
                <span>{shown.title}</span>
                {shown.note && <small>{shown.note}</small>}
              </div>
            )}
          </div>
        </div>
      ) : (
        <div className="tw" style={{ maxHeight: 260 }}>
          <table className="dense">
            <thead><tr><th>{what}</th><th className="n">Sales</th><th className="n">{noteHead}</th></tr></thead>
            <tbody>
              {points.map((p) => (
                <tr key={p.key}><td>{p.title}</td><td className="n">{peso(p.value)}</td><td className="n">{p.orders ?? ""}</td></tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
