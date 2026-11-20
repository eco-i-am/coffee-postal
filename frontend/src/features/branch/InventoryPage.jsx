import { useCallback, useState } from "react";
import { api } from "../../shared/api.js";
import { useLoad } from "../../shared/hooks.js";
import { MOVE_NAMES, dateTimeOf, fmtQty } from "../../shared/format.js";
import { useAuth } from "../../app/AuthContext.jsx";
import { Thumb } from "../../shared/ImageField.jsx";
import { useImages } from "../../shared/images.js";
import PagedRows from "../../shared/PagedRows.jsx";

const loadStock = () => api("/api/branch/inventory");

function AdjustModal({ item, onClose, onDone }) {
  const [kind, setKind] = useState("WASTE");
  const [qty, setQty] = useState("");
  const [sign, setSign] = useState(1);
  const [reason, setReason] = useState("");
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(e) {
    e.preventDefault();
    const n = Number(qty);
    if (!Number.isInteger(n) || n <= 0) { setErr("Enter a whole number greater than zero."); return; }
    setBusy(true); setErr("");
    try {
      await api("/api/branch/inventory/adjust", {
        method: "POST",
        body: { itemId: item.itemId, kind, delta: kind === "WASTE" ? n : sign * n, reason },
      });
      onDone(`${item.name} updated.`);
    } catch (e2) { setErr(e2.message); setBusy(false); }
  }

  return (
    <div className="scrim" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <form className="modal" role="dialog" aria-modal="true" onSubmit={submit}>
        <h2>{item.name}</h2>
        <p className="hint">On hand now: <b>{fmtQty(item.onHand)} {item.unit}</b></p>
        <div className="opts" role="group" aria-label="What happened">
          <button type="button" className="chip" aria-pressed={kind === "WASTE"} onClick={() => setKind("WASTE")}>Waste / spoiled</button>
          <button type="button" className="chip" aria-pressed={kind === "ADJUST"} onClick={() => setKind("ADJUST")}>Count correction</button>
        </div>
        {kind === "ADJUST" && (
          <div className="opts" role="group" aria-label="Direction">
            <button type="button" className="chip" aria-pressed={sign === 1} onClick={() => setSign(1)}>Add</button>
            <button type="button" className="chip" aria-pressed={sign === -1} onClick={() => setSign(-1)}>Remove</button>
          </div>
        )}
        <div className="addr"><label htmlFor="aqty">How many ({item.unit})</label>
          <input id="aqty" value={qty} onChange={(e) => setQty(e.target.value)} inputMode="numeric" autoFocus required /></div>
        <div className="addr"><label htmlFor="areason">Reason</label>
          <input id="areason" value={reason} onChange={(e) => setReason(e.target.value)} placeholder={kind === "WASTE" ? "Expired, dropped, spilled..." : "Monthly count"} required /></div>
        {err && <p className="err" role="alert">{err}</p>}
        <div className="opts">
          <button type="button" className="btn ghost" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn warm" style={{ flex: 1 }} disabled={busy}>Save</button>
        </div>
      </form>
    </div>
  );
}

function History({ item, onClose }) {
  const load = useCallback(() => api(`/api/branch/inventory/${item.itemId}/movements`), [item.itemId]);
  const { data, error } = useLoad(load);
  return (
    <div className="scrim" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal" role="dialog" aria-modal="true">
        <h2>{item.name}</h2>
        {error && <p className="err" role="alert">{error}</p>}
        {!data && !error && <p role="status">Loading...</p>}
        {data && (
          <div className="tw">
            <table>
              <thead><tr><th>When</th><th>What</th><th className="n">Qty</th><th>Note</th></tr></thead>
              <tbody>
                {data.map((m) => (
                  <tr key={m.id}>
                    <td>{dateTimeOf(m.at)}</td><td>{MOVE_NAMES[m.kind] || m.kind}</td>
                    <td className="n"><b>{m.qty > 0 ? `+${fmtQty(m.qty)}` : fmtQty(m.qty)}</b></td>
                    <td>{[m.ref, m.reason, m.by].filter(Boolean).join(" · ")}</td>
                  </tr>
                ))}
                {data.length === 0 && <tr><td colSpan="4" className="emptyrow">No stock changes yet.</td></tr>}
              </tbody>
            </table>
          </div>
        )}
        <button className="btn ghost" onClick={onClose}>Close</button>
      </div>
    </div>
  );
}

export default function InventoryPage() {
  const { can } = useAuth();
  const { data: rows, error, loading, reload } = useLoad(loadStock);
  const imgs = useImages();
  const [adjust, setAdjust] = useState(null);
  const [history, setHistory] = useState(null);
  const [msg, setMsg] = useState("");
  const [onlyLow, setOnlyLow] = useState(false);

  const shown = (rows || []).filter((r) => !onlyLow || r.low);
  const lowCount = (rows || []).filter((r) => r.low).length;

  return (
    <section>
      <header className="pghead">
        <div><h1>Item Inventory</h1><p>What this branch has on hand. Stock goes up when an order from HQ is received.</p></div>
        <button className="chip" aria-pressed={onlyLow} onClick={() => setOnlyLow(!onlyLow)}>
          Low stock only{lowCount > 0 ? ` (${lowCount})` : ""}
        </button>
      </header>
      {msg && <p className="notice" role="status">{msg}</p>}
      {error && <p className="notice" role="alert">{error}</p>}
      <div className="sec tw">
        {loading && !rows ? <p role="status">Loading...</p> : (
          <table>
            <thead><tr><th></th><th>Item</th><th className="n">On hand</th><th>Unit</th><th>Status</th><th></th></tr></thead>
            <tbody>
              <PagedRows rows={shown} render={(r) => (
                <tr key={r.itemId}>
                  <td className="thc"><Thumb url={imgs.url("item", r.itemId)} alt="" /></td>
                  <td><b>{r.name}</b></td>
                  <td className={`n${r.onHand < 0 ? " short" : ""}`}><b>{fmtQty(r.onHand)}</b></td>
                  <td>{r.unit}</td>
                  <td>{r.low ? <span className="tag r">Low - reorder</span> : <span className="tag g">OK</span>}</td>
                  <td className="rowbtns">
                    <button className="btn ghost sm" onClick={() => setHistory(r)}>History</button>
                    {can("branch_inventory_edit") && <button className="btn ghost sm" onClick={() => setAdjust(r)}>Adjust</button>}
                  </td>
                </tr>
              )} />
              {rows && shown.length === 0 && <tr><td colSpan="6" className="emptyrow">{onlyLow ? "Nothing is low. Nice." : "HQ has not added any items yet."}</td></tr>}
            </tbody>
          </table>
        )}
      </div>
      {adjust && <AdjustModal item={adjust} onClose={() => setAdjust(null)} onDone={(m) => { setAdjust(null); setMsg(m); reload(); }} />}
      {history && <History item={history} onClose={() => setHistory(null)} />}
    </section>
  );
}
