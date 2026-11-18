import { useCallback, useState } from "react";
import { api } from "../../shared/api.js";
import { useLoad } from "../../shared/hooks.js";
import { peso } from "../../shared/money.js";
import { dateTimeOf } from "../../shared/format.js";
import { useAuth } from "../../app/AuthContext.jsx";
import { useSync } from "../../app/SyncContext.jsx";
import ReadingSheet from "./ReadingSheet.jsx";

const loadShift = () => api("/api/branch/shift");
const loadHistory = () => api("/api/branch/shifts");

function Kpi({ label, value, note }) {
  return <div className="kpi"><small>{label}</small><b>{value}</b>{note && <span>{note}</span>}</div>;
}

function OpenShift({ onDone }) {
  const [cash, setCash] = useState("");
  const [err, setErr] = useState("");
  async function submit(e) {
    e.preventDefault();
    setErr("");
    try { await api("/api/branch/shift/open", { method: "POST", body: { openingCash: cash } }); onDone(); }
    catch (e2) { setErr(e2.message); }
  }
  return (
    <form className="sec stack" onSubmit={submit} style={{ maxWidth: 460 }}>
      <h2>Start your shift</h2>
      <p className="hint">Count the cash in the drawer and type it in. This is your opening float.</p>
      <div className="addr"><label htmlFor="oc">Cash in drawer (&#8369;)</label>
        <input id="oc" inputMode="decimal" value={cash} onChange={(e) => setCash(e.target.value)} placeholder="1000.00" autoFocus required /></div>
      {err && <p className="err" role="alert">{err}</p>}
      <button className="btn warm" type="submit">Open shift</button>
    </form>
  );
}

function CloseShift({ shift, onClose, onDone }) {
  const [counted, setCounted] = useState("");
  const [err, setErr] = useState("");
  const [result, setResult] = useState(null);
  const [z, setZ] = useState(null);
  async function submit(e) {
    e.preventDefault();
    setErr("");
    try { setResult(await api("/api/branch/shift/close", { method: "POST", body: { countedCash: counted } })); }
    catch (e2) { setErr(e2.message); }
  }
  async function showZ() {
    setErr("");
    try { setZ(await api(`/api/branch/shifts/${result.id}/reading`)); }
    catch (e2) { setErr(e2.message); }
  }
  if (z) {
    return (
      <div className="scrim">
        <div className="modal" role="dialog" aria-modal="true"><ReadingSheet r={z} onClose={onDone} /></div>
      </div>
    );
  }
  return (
    <div className="scrim" onClick={(e) => e.target === e.currentTarget && !result && onClose()}>
      <form className="modal" role="dialog" aria-modal="true" onSubmit={submit}>
        {!result ? (
          <>
            <h2>Close your shift</h2>
            <p className="hint">Count all the cash in the drawer and type it in. You will see if it matches.</p>
            <div className="addr"><label htmlFor="cc">Cash counted (&#8369;)</label>
              <input id="cc" inputMode="decimal" value={counted} onChange={(e) => setCounted(e.target.value)} autoFocus required /></div>
            {err && <p className="err" role="alert">{err}</p>}
            <div className="opts">
              <button type="button" className="btn ghost" onClick={onClose}>Back</button>
              <button type="submit" className="btn warm" style={{ flex: 1 }}>Close shift</button>
            </div>
          </>
        ) : (
          <>
            <h2>Shift closed</h2>
            <div className="receipt">
              <div className="r"><span>Opened</span><span>{dateTimeOf(result.openedAt)}</span></div>
              <div className="r"><span>Expected in drawer</span><span>{peso(result.expected)}</span></div>
              <div className="r"><span>You counted</span><span>{peso(result.counted)}</span></div>
              <hr />
              <div className="r"><b>{result.variance === 0 ? "Exact" : result.variance < 0 ? "Short" : "Over"}</b><b>{peso(Math.abs(result.variance))}</b></div>
            </div>
            {err && <p className="err" role="alert">{err}</p>}
            <div className="opts">
              <button type="button" className="btn ghost" onClick={onDone}>Done</button>
              <button type="button" className="btn warm" style={{ flex: 1 }} onClick={showZ}>Z reading #{result.zNo}</button>
            </div>
          </>
        )}
      </form>
    </div>
  );
}

export default function CashPage() {
  const { can } = useAuth();
  const { data, error, loading, reload } = useLoad(loadShift);
  const noHistory = useCallback(() => Promise.resolve(null), []);
  const history = useLoad(can("cashier_reports_view") ? loadHistory : noHistory);
  const [kind, setKind] = useState("OUT");
  const [amount, setAmount] = useState("");
  const [reason, setReason] = useState("");
  const [err, setErr] = useState("");
  const [closing, setClosing] = useState(false);
  const [reading, setReading] = useState(null);
  const sync = useSync();
  const waiting = sync.pending + sync.rejected.length;

  async function openReading(path) {
    setErr("");
    try { setReading(await api(path)); } catch (e2) { setErr(e2.message); }
  }

  const s = data?.shift;

  async function move(e) {
    e.preventDefault();
    setErr("");
    try {
      await api("/api/branch/shift/cash", { method: "POST", body: { kind, amount, reason } });
      setAmount(""); setReason("");
      reload();
    } catch (e2) { setErr(e2.message); }
  }

  return (
    <section>
      <header className="pghead"><div><h1>Cash Drawer</h1><p>Your shift: opening cash, cash in and out, and the end-of-shift count.</p></div></header>
      {error && <p className="notice" role="alert">{error}</p>}
      {loading && !data && <p role="status">Loading...</p>}

      {data && !s && <OpenShift onDone={reload} />}

      {s && (
        <>
          <div className="kpis">
            <Kpi label="Should be in drawer" value={peso(s.expected)} note={`opened ${dateTimeOf(s.openedAt)}`} />
            <Kpi label="Opening cash" value={peso(s.openingCash)} />
            <Kpi label="Cash sales" value={peso(s.cashSales)} note={`${s.orders} orders, ${peso(s.sales)} in all`} />
            <Kpi label="Cash in / out" value={`${peso(s.cashIn)} / ${peso(s.cashOut)}`} />
          </div>
          <div className="two">
            <form className="sec stack" onSubmit={move}>
              <h2>Cash in or out</h2>
              <div className="opts" role="group" aria-label="Direction">
                <button type="button" className="chip" aria-pressed={kind === "OUT"} onClick={() => setKind("OUT")}>Cash out</button>
                <button type="button" className="chip" aria-pressed={kind === "IN"} onClick={() => setKind("IN")}>Cash in</button>
              </div>
              <div className="addr"><label htmlFor="ca">Amount (&#8369;)</label>
                <input id="ca" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} required /></div>
              <div className="addr"><label htmlFor="cr">Reason</label>
                <input id="cr" value={reason} onChange={(e) => setReason(e.target.value)} placeholder={kind === "OUT" ? "Ice run, supplies..." : "Change from the safe..."} required /></div>
              {err && <p className="err" role="alert">{err}</p>}
              <button className="btn warm" type="submit">Save</button>
              <button className="btn ghost" type="button" onClick={() => openReading("/api/branch/shift/reading")}>X reading</button>
              <button className="btn ghost" type="button" disabled={waiting > 0} onClick={() => setClosing(true)}>Close my shift</button>
              {waiting > 0 && (
                <p className="notice" role="status">
                  {waiting} {waiting === 1 ? "order is" : "orders are"} still waiting to upload from this device. Upload {waiting === 1 ? "it" : "them"} (or ask a manager about rejected ones) before closing, or the drawer will not add up.
                </p>
              )}
            </form>
            <div className="sec tw">
              <h2>Cash in / out today</h2>
              <table>
                <thead><tr><th>When</th><th>What</th><th className="n">Amount</th></tr></thead>
                <tbody>
                  {s.movements.map((m) => (
                    <tr key={m.id}><td>{dateTimeOf(m.at)}</td><td>{m.reason}</td>
                      <td className="n"><b>{m.kind === "IN" ? "+" : "-"}{peso(m.amount)}</b></td></tr>
                  ))}
                  {s.movements.length === 0 && <tr><td colSpan="3" className="emptyrow">Nothing yet.</td></tr>}
                </tbody>
              </table>
            </div>
          </div>
        </>
      )}

      {history.data && (
        <div className="sec tw">
          <h2>Past shifts</h2>
          <table>
            <thead><tr><th>Cashier</th><th>Opened</th><th>Closed</th><th className="n">Expected</th><th className="n">Counted</th><th className="n">Over / short</th><th></th></tr></thead>
            <tbody>
              {history.data.map((h) => (
                <tr key={h.id}>
                  <td><b>{h.cashier}</b></td><td>{dateTimeOf(h.openedAt)}</td><td>{h.closedAt ? dateTimeOf(h.closedAt) : <span className="tag y">Open</span>}</td>
                  <td className="n">{h.expected != null ? peso(h.expected) : ""}</td>
                  <td className="n">{h.counted != null ? peso(h.counted) : ""}</td>
                  <td className={`n${h.variance < 0 ? " short" : ""}`}>{h.variance != null ? (h.variance === 0 ? "Exact" : peso(h.variance)) : ""}</td>
                  <td className="rowbtns"><button className="btn ghost sm" type="button" onClick={() => openReading(`/api/branch/shifts/${h.id}/reading`)}>{h.zNo ? `Z #${h.zNo}` : "X"}</button></td>
                </tr>
              ))}
              {history.data.length === 0 && <tr><td colSpan="7" className="emptyrow">No shifts yet.</td></tr>}
            </tbody>
          </table>
        </div>
      )}

      {reading && (
        <div className="scrim" onClick={(e) => e.target === e.currentTarget && setReading(null)}>
          <div className="modal" role="dialog" aria-modal="true"><ReadingSheet r={reading} onClose={() => setReading(null)} /></div>
        </div>
      )}
      {closing && s && <CloseShift shift={s} onClose={() => setClosing(false)} onDone={() => { setClosing(false); reload(); history.reload(); }} />}
    </section>
  );
}
