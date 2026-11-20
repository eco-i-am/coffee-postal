import { useCallback, useState } from "react";
import { api } from "../../shared/api.js";
import { useLoad } from "../../shared/hooks.js";
import { useAuth } from "../../app/AuthContext.jsx";
import { dateTimeOf } from "../../shared/format.js";
import PagedRows from "../../shared/PagedRows.jsx";

const TONE = { SENT: ["y", "On the way"], RECEIVED: ["g", "Received"], CANCELLED: ["r", "Cancelled"] };
const qtyText = (t) => String(t / 1000);

/** Stock sent between branches. Branches send, receive and cancel; HQ only watches. */
export default function TransfersPage({ side }) {
  const hq = side === "hq";
  const { can, me } = useAuth();
  const load = useCallback(() => api(hq ? "/api/admin/transfers" : "/api/branch/transfers"), [hq]);
  const { data, error, loading, reload } = useLoad(load);
  const loadBranches = useCallback(() => (hq ? Promise.resolve([]) : api("/api/branch/transfers/branches")), [hq]);
  const branches = useLoad(loadBranches).data || [];
  const loadStock = useCallback(() => (hq ? Promise.resolve([]) : api("/api/branch/inventory")), [hq]);
  const stock = useLoad(loadStock).data || [];

  const [msg, setMsg] = useState("");
  const [to, setTo] = useState("");
  const [note, setNote] = useState("");
  const [lines, setLines] = useState([{ itemId: "", qty: "" }]);
  const [busy, setBusy] = useState(false);

  const setLine = (n, patch) => setLines(lines.map((l, k) => (k === n ? { ...l, ...patch } : l)));

  async function send(e) {
    e.preventDefault();
    setMsg("");
    const body = {
      toBranchId: Number(to), note,
      lines: lines.filter((l) => l.itemId && l.qty).map((l) => ({ itemId: Number(l.itemId), qty: l.qty })),
    };
    setBusy(true);
    try {
      await api("/api/branch/transfers", { method: "POST", body });
      setMsg("Sent. The other branch adds it to their stock when they confirm it arrived.");
      setTo(""); setNote(""); setLines([{ itemId: "", qty: "" }]);
      reload();
    } catch (err) { setMsg(err.message); } finally { setBusy(false); }
  }

  async function act(t, what) {
    setMsg("");
    try {
      await api(`/api/branch/transfers/${t.id}/${what}`, { method: "POST" });
      setMsg(what === "receive" ? "Received. It is in your stock now." : "Cancelled. The stock is back with you.");
      reload();
    } catch (err) { setMsg(err.message); reload(); }
  }

  const rows = data || [];
  const myBranchName = me?.branchName;

  return (
    <section>
      <header className="pghead"><div>
        <h1>Stock Transfers</h1>
        <p>{hq ? "Stock moved between branches." : "Send stock to another branch, and confirm what is sent to you."}</p>
      </div></header>
      {msg && <p className="notice" role="status">{msg}</p>}
      {error && <p className="err" role="alert">{error}</p>}

      {!hq && can("transfers_add") && (
        <div className="sec">
          <h2>Send stock</h2>
          <form onSubmit={send} className="stack" noValidate>
            <div className="addr"><label htmlFor="tto">To branch</label>
              <select className="line" id="tto" value={to} onChange={(e) => setTo(e.target.value)} required>
                <option value="">Pick a branch</option>
                {branches.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
              </select></div>
            {lines.map((l, n) => {
              const it = stock.find((s) => String(s.itemId) === String(l.itemId));
              return (
                <div className="opts" key={n}>
                  <select className="line" aria-label="Item" value={l.itemId} onChange={(e) => setLine(n, { itemId: e.target.value })} style={{ flex: 2, minWidth: 160 }}>
                    <option value="">Pick an item</option>
                    {stock.map((s) => <option key={s.itemId} value={s.itemId}>{s.name} ({s.onHand / 1000} {s.unit} on hand)</option>)}
                  </select>
                  <input aria-label="Quantity" value={l.qty} inputMode="decimal" placeholder={it ? it.unit : "Qty"} onChange={(e) => setLine(n, { qty: e.target.value })} style={{ width: 90 }} />
                  {lines.length > 1 && <button type="button" className="btn ghost sm" onClick={() => setLines(lines.filter((_, k) => k !== n))}>Remove</button>}
                </div>
              );
            })}
            <button type="button" className="btn ghost sm" onClick={() => setLines([...lines, { itemId: "", qty: "" }])}>Add another item</button>
            <div className="addr"><label htmlFor="tnote">Note (optional)</label><input id="tnote" value={note} maxLength={200} onChange={(e) => setNote(e.target.value)} /></div>
            <button className="btn warm" type="submit" disabled={busy}>Send</button>
          </form>
        </div>
      )}

      <div className="sec tw">
        <h2>Transfers</h2>
        {loading && !data ? <p role="status">Loading...</p> : (
          <table>
            <thead><tr><th>#</th><th>From</th><th>To</th><th>Items</th><th>Status</th><th>Sent</th><th></th></tr></thead>
            <tbody>
              <PagedRows rows={rows} render={(t) => {
                const [tone, label] = TONE[t.status] || ["y", t.status];
                const incoming = !hq && t.toBranch === myBranchName;
                const outgoing = !hq && !incoming;
                return (
                  <tr key={t.id}>
                    <td>TR-{t.id}</td><td>{t.fromBranch}</td><td>{t.toBranch}</td>
                    <td>{t.lines.map((l) => <div key={l.itemId}>{qtyText(l.qty)} {l.unit} {l.item}</div>)}{t.note && <small>{t.note}</small>}</td>
                    <td><span className={`tag ${tone}`}>{label}</span></td>
                    <td>{dateTimeOf(t.createdAt)}</td>
                    <td className="rowbtns">
                      {t.status === "SENT" && can("transfers_edit") && !hq && (incoming
                        ? <button className="btn warm sm" type="button" onClick={() => act(t, "receive")}>Mark received</button>
                        : outgoing && <button className="btn ghost sm" type="button" onClick={() => act(t, "cancel")}>Cancel</button>)}
                    </td>
                  </tr>
                );
              }} />
              {rows.length === 0 && <tr><td colSpan="7" className="emptyrow">No transfers yet.</td></tr>}
            </tbody>
          </table>
        )}
      </div>
    </section>
  );
}
