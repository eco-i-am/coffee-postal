import { useEffect, useRef, useState } from "react";
import { api } from "../../shared/api.js";
import { peso } from "../../shared/money.js";
import { dateTimeOf } from "../../shared/format.js";
import { useSync } from "../../app/SyncContext.jsx";
import ReadingSheet from "./ReadingSheet.jsx";
import DenomCounter from "../../shared/DenomCounter.jsx";
import { captureJpeg, startCamera, stopCamera } from "../../shared/camera.js";

export function Kpi({ label, value, note }) {
  return <div className="kpi"><small>{label}</small><b>{value}</b>{note && <span>{note}</span>}</div>;
}

export function OpenShift({ onDone }) {
  const [cash, setCash] = useState("");
  const [err, setErr] = useState("");
  const videoRef = useRef(null);
  const [camera, setCamera] = useState("starting");   // starting | on | off
  useEffect(() => {
    let stream = null;
    let alive = true;
    startCamera(videoRef.current).then((s) => { if (!alive) { stopCamera(s); return; } stream = s; setCamera(s ? "on" : "off"); });
    return () => { alive = false; stopCamera(stream); };
  }, []);
  async function submit(e) {
    e.preventDefault();
    setErr("");
    const photo = captureJpeg(videoRef.current);
    if (!photo) { setErr("Allow the camera for this site, then try again. A photo is needed to open your shift."); return; }
    try { await api("/api/branch/shift/open", { method: "POST", body: { openingCash: cash, photo } }); onDone(); }
    catch (e2) { setErr(e2.message); }
  }
  return (
    <form className="sec stack" onSubmit={submit} style={{ maxWidth: 460 }}>
      <h2>Start your shift</h2>
      <p className="hint">Count the cash in the drawer and type it in, or count the bills and coins and the total fills in. This is your opening float.</p>
      <div className="addr"><label htmlFor="oc">Cash in drawer (&#8369;)</label>
        <input id="oc" inputMode="decimal" value={cash} onChange={(e) => setCash(e.target.value)} placeholder="1000.00" autoFocus required /></div>
      <DenomCounter onTotal={setCash} />
      <video ref={videoRef} className="preview" muted playsInline aria-label="Camera preview" hidden={camera !== "on"} style={{ width: "100%", maxHeight: 200, borderRadius: 12, objectFit: "cover", transform: "scaleX(-1)" }} />
      {camera === "off" && <p className="notice" role="status">The camera is off. Allow it for this site in the browser settings.</p>}
      <p className="hint">A photo is taken when you open the shift. Only your manager can see it, and it is deleted after a while.</p>
      {err && <p className="err" role="alert">{err}</p>}
      <button className="btn warm" type="submit">Take photo and open shift</button>
    </form>
  );
}

export function CloseShift({ shift, onClose, onDone }) {
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
            <p className="hint">Count all the cash in the drawer and type it in, or count the bills and coins and the total fills in. You will see if it matches.</p>
            <div className="addr"><label htmlFor="cc">Cash counted (&#8369;)</label>
              <input id="cc" inputMode="decimal" value={counted} onChange={(e) => setCounted(e.target.value)} autoFocus required /></div>
            <DenomCounter onTotal={setCounted} />
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


/** The open shift: what should be in the drawer, cash in and out, the X reading, and closing the shift. */
export function ShiftPanel({ s, reload, onClosed }) {
  const [kind, setKind] = useState("OUT");
  const [amount, setAmount] = useState("");
  const [reason, setReason] = useState("");
  const [err, setErr] = useState("");
  const [closing, setClosing] = useState(false);
  const [tally, setTally] = useState(0);   // bumps after a save so the bill and coin counter starts empty again
  const [reading, setReading] = useState(null);
  const sync = useSync();
  const waiting = sync.pending + sync.rejected.length;

  async function openReading(path) {
    setErr("");
    try { setReading(await api(path)); } catch (e2) { setErr(e2.message); }
  }

  async function move(e) {
    e.preventDefault();
    setErr("");
    try {
      await api("/api/branch/shift/cash", { method: "POST", body: { kind, amount, reason } });
      setAmount(""); setReason(""); setTally((n) => n + 1);
      reload();
    } catch (e2) { setErr(e2.message); }
  }

  return (
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
          <DenomCounter key={tally} onTotal={setAmount} />
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

      {reading && (
        <div className="scrim" onClick={(e) => e.target === e.currentTarget && setReading(null)}>
          <div className="modal" role="dialog" aria-modal="true"><ReadingSheet r={reading} onClose={() => setReading(null)} /></div>
        </div>
      )}
      {closing && <CloseShift shift={s} onClose={() => setClosing(false)} onDone={() => { setClosing(false); reload(); onClosed?.(); }} />}
    </>
  );
}
