import { useCallback, useState } from "react";
import { api } from "../../shared/api.js";
import { useLoad } from "../../shared/hooks.js";
import MoreRows from "../../shared/MoreRows.jsx";
import { peso } from "../../shared/money.js";
import { PO_STATUS, dateTimeOf, fmtQty, poNo, poTone } from "../../shared/format.js";
import { useAuth } from "../../app/AuthContext.jsx";
import PoDetail from "../po/PoDetail.jsx";

const loadItems = () => api("/api/branch/inventory");

function NewPo({ onClose, onCreated }) {
  const { data: items, error } = useLoad(loadItems);
  const [qty, setQty] = useState({});
  const [note, setNote] = useState("");
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);

  const picked = (items || []).filter((i) => Number(qty[i.itemId]) > 0);
  const total = picked.reduce((s, i) => s + i.cost * Number(qty[i.itemId]), 0);
  const invalid = Object.values(qty).some((v) => v !== "" && (!/^\d+$/.test(v) || Number(v) > 100000));

  async function send(e) {
    e.preventDefault();
    setBusy(true); setErr("");
    try {
      const r = await api("/api/branch/purchase-orders", {
        method: "POST",
        body: { note, lines: picked.map((i) => ({ itemId: i.itemId, qty: Number(qty[i.itemId]) })) },
      });
      onCreated(r.id);
    } catch (e2) { setErr(e2.message); setBusy(false); }
  }

  return (
    <div className="scrim" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <form className="modal wide" role="dialog" aria-modal="true" onSubmit={send}>
        <h2>New purchase order</h2>
        <p className="hint">Type how many of each item you want from HQ. Leave the rest empty.</p>
        {error && <p className="err" role="alert">{error}</p>}
        {!items && !error && <p role="status">Loading...</p>}
        {items && (
          <div className="tw">
            <table>
              <thead><tr><th>Item</th><th className="n">You have</th><th className="n">Cost</th><th className="n">Order</th></tr></thead>
              <tbody>
                {items.map((i) => (
                  <tr key={i.itemId}>
                    <td><b>{i.name}</b> <small>({i.unit})</small>{i.low && <> <span className="tag r">Low</span></>}</td>
                    <td className="n">{fmtQty(i.onHand)}</td>
                    <td className="n">{peso(i.cost)}</td>
                    <td className="n"><input className="qtyin" aria-label={`Order ${i.name}`} inputMode="numeric" value={qty[i.itemId] || ""} onChange={(e) => setQty({ ...qty, [i.itemId]: e.target.value.trim() })} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <div className="addr"><label htmlFor="ponote">Note for HQ (optional)</label>
          <input id="ponote" value={note} onChange={(e) => setNote(e.target.value)} maxLength={300} /></div>
        {err && <p className="err" role="alert">{err}</p>}
        <div className="opts">
          <button type="button" className="btn ghost" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn warm" style={{ flex: 1 }} disabled={busy || picked.length === 0 || invalid}>
            Send to HQ{picked.length > 0 ? ` · ${picked.length} ${picked.length === 1 ? "item" : "items"} · ${peso(total)}` : ""}
          </button>
        </div>
      </form>
    </div>
  );
}

export default function PurchaseOrdersPage() {
  const { can } = useAuth();
  const [limit, setLimit] = useState(100);
  const loadPos = useCallback(() => api(`/api/branch/purchase-orders?limit=${limit}`), [limit]);
  const { data: rows, error, loading, reload } = useLoad(loadPos);
  const [creating, setCreating] = useState(false);
  const [open, setOpen] = useState(null);
  const [msg, setMsg] = useState("");

  return (
    <section>
      <header className="pghead">
        <div><h1>Purchase Orders</h1><p>Order supplies from HQ and confirm what arrives.</p></div>
        {can("branch_po_add") && <button className="btn warm" onClick={() => setCreating(true)}>New order</button>}
      </header>
      {msg && <p className="notice" role="status">{msg}</p>}
      {error && <p className="notice" role="alert">{error}</p>}
      <div className="sec tw">
        {loading && !rows ? <p role="status">Loading...</p> : (
          <table>
            <thead><tr><th>No.</th><th>Asked</th><th className="n">Items</th><th className="n">Value</th><th>Status</th></tr></thead>
            <tbody>
              {(rows || []).map((r) => (
                <tr key={r.id} className="clickrow" tabIndex={0} onClick={() => setOpen(r.id)} onKeyDown={(e) => e.key === "Enter" && setOpen(r.id)}>
                  <td><b>{poNo(r.id)}</b></td><td>{dateTimeOf(r.requestedAt)}</td><td className="n">{r.lines}</td><td className="n">{peso(r.value)}</td>
                  <td><span className={`tag ${poTone(r.status)}`}>{PO_STATUS[r.status]}</span></td>
                </tr>
              ))}
              {rows && rows.length === 0 && <tr><td colSpan="5" className="emptyrow">No purchase orders yet.</td></tr>}
            </tbody>
          </table>
        )}
        <MoreRows count={(rows || []).length} limit={limit} onMore={() => setLimit(limit + 100)} />
      </div>
      {creating && <NewPo onClose={() => setCreating(false)} onCreated={(id) => { setCreating(false); setMsg(`${poNo(id)} sent to HQ.`); reload(); setOpen(id); }} />}
      {open && <PoDetail id={open} side="branch" onClose={() => setOpen(null)} onChanged={(m) => { setMsg(m); reload(); }} />}
    </section>
  );
}
