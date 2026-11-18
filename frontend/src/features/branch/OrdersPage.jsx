import { useCallback, useState } from "react";
import { api } from "../../shared/api.js";
import { useLoad } from "../../shared/hooks.js";
import MoreRows from "../../shared/MoreRows.jsx";
import { peso } from "../../shared/money.js";
import {
  DISCOUNT_NAMES, METHOD_NAMES, STATUS_NAMES, TYPE_NAMES, prettyDate, timeOf, todayManila,
} from "../../shared/format.js";

function RefundForm({ id, onDone, onCancel }) {
  const [reason, setReason] = useState("");
  const [pin, setPin] = useState("");
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(e) {
    e.preventDefault();
    setBusy(true); setErr("");
    try {
      await api(`/api/branch/orders/${id}/refund`, { method: "POST", body: { reason, pin } });
      onDone();
    } catch (e2) { setErr(e2.message); setBusy(false); setPin(""); }
  }

  return (
    <form className="stack" style={{ gap: 10 }} onSubmit={submit}>
      <div className="addr"><label htmlFor="rfr">Why is it being refunded?</label>
        <input id="rfr" value={reason} onChange={(e) => setReason(e.target.value)} autoFocus required /></div>
      <div className="addr"><label htmlFor="rfp">Manager PIN</label>
        <input id="rfp" type="password" inputMode="numeric" autoComplete="off" value={pin} onChange={(e) => setPin(e.target.value)} required /></div>
      {err && <p className="err" role="alert">{err}</p>}
      <div className="opts">
        <button type="button" className="btn ghost" onClick={onCancel}>Back</button>
        <button type="submit" className="btn" style={{ flex: 1 }} disabled={busy || !reason.trim() || !pin}>Refund this order</button>
      </div>
    </form>
  );
}

function OrderDetail({ id, onClose, onChanged }) {
  const load = useCallback(() => api(`/api/branch/orders/${id}`), [id]);
  const { data: d, error, reload } = useLoad(load);
  const [refunding, setRefunding] = useState(false);
  return (
    <div className="scrim" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal" role="dialog" aria-modal="true" aria-label="Order details">
        {error && <p className="err" role="alert">{error}</p>}
        {!d && !error && <p role="status">Loading...</p>}
        {d && (
          <>
            <h2>Order #{d.order.orderNo} <span className={`tag ${d.order.status === "PAID" ? "g" : "r"}`}>{STATUS_NAMES[d.order.status]}</span></h2>
            <p className="hint">
              {timeOf(d.order.createdAt)} &middot; {TYPE_NAMES[d.order.orderType]} &middot; Cashier {d.order.cashier}
            </p>
            <div className="receipt">
              {d.lines.map((l, i) => (
                <div key={i}>
                  <div className="r"><span>{l.qty} x {l.name}</span><span>{peso(l.unitPrice * l.qty)}</span></div>
                  {(l.variant || l.addons) && <div className="optline">{[l.variant, l.addons].filter(Boolean).join(", ")}</div>}
                </div>
              ))}
              <hr />
              <div className="r"><span>Subtotal</span><span>{peso(d.subtotal)}</span></div>
              {d.discount > 0 && <div className="r"><span>{DISCOUNT_NAMES[d.discountType]}</span><span>-{peso(d.discount)}</span></div>}
              <div className="r"><b>TOTAL</b><b>{peso(d.order.total)}</b></div>
              {d.payments.map((p, i) => (
                <div key={i}>
                  <div className="r"><span>{METHOD_NAMES[p.method]}</span><span>{peso(p.tendered)}</span></div>
                  {p.method === "CASH" && p.tendered > p.amount && <div className="r"><span>Change</span><span>{peso(p.tendered - p.amount)}</span></div>}
                  {p.reference && <div className="optline">Ref {p.reference}</div>}
                </div>
              ))}
              <hr />
              <div className="r"><span>VATable</span><span>{peso(d.vatable)}</span></div>
              <div className="r"><span>VAT 12%</span><span>{peso(d.vat)}</span></div>
            </div>
          </>
        )}
        {d?.refund && (
          <p className="notice" role="status">
            Refunded {timeOf(d.refund.at)}: {d.refund.reason}. Approved by {d.refund.approvedBy}.
          </p>
        )}
        {d && d.order.status === "PAID" && refunding && (
          <RefundForm id={id} onCancel={() => setRefunding(false)} onDone={() => { setRefunding(false); onChanged("Order refunded."); reload(); }} />
        )}
        {!refunding && (
          <div className="opts">
            <button className="btn ghost" onClick={onClose}>Close</button>
            {d && d.order.status === "PAID" && <button className="btn ghost" onClick={() => setRefunding(true)}>Refund</button>}
          </div>
        )}
      </div>
    </div>
  );
}

export default function OrdersPage() {
  const [date, setDate] = useState(todayManila());
  const [status, setStatus] = useState("");
  const [no, setNo] = useState("");
  const [open, setOpen] = useState(null);

  const [limit, setLimit] = useState(100);
  const load = useCallback(() => {
    const q = new URLSearchParams({ date, limit });
    if (status) q.set("status", status);
    if (/^\d+$/.test(no.trim())) q.set("no", no.trim());
    return api(`/api/branch/orders?${q}`);
  }, [date, status, no, limit]);
  const { data: rows, error, loading, reload } = useLoad(load);
  const [msg, setMsg] = useState("");

  const total = (rows || []).filter((o) => o.status === "PAID").reduce((s, o) => s + o.total, 0);

  return (
    <section>
      <header className="pghead">
        <div>
          <h1>Order List</h1>
          <p>{prettyDate(date)}</p>
        </div>
      </header>

      <div className="filters">
        <label className="datepick">Date
          <input type="date" value={date} max={todayManila()} onChange={(e) => e.target.value && setDate(e.target.value)} />
        </label>
        <label className="datepick">Status
          <select value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="">All</option>
            {Object.entries(STATUS_NAMES).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select>
        </label>
        <label className="datepick">Order no.
          <input inputMode="numeric" value={no} onChange={(e) => setNo(e.target.value)} placeholder="e.g. 12" />
        </label>
      </div>

      {error && <p className="notice" role="alert">{error}</p>}
      <div className="sec tw">
        {loading && !rows ? <p role="status">Loading...</p> : (
          <table>
            <thead><tr><th>No.</th><th>Time</th><th>Type</th><th>Paid by</th><th>Cashier</th><th className="n">Total</th><th>Status</th></tr></thead>
            <tbody>
              {(rows || []).map((o) => (
                <tr key={o.id} className="clickrow" tabIndex={0} onClick={() => setOpen(o.id)}
                    onKeyDown={(e) => e.key === "Enter" && setOpen(o.id)}>
                  <td><b>#{o.orderNo}</b></td>
                  <td>{timeOf(o.createdAt)}</td>
                  <td>{TYPE_NAMES[o.orderType]}</td>
                  <td>{(o.method || "").split(", ").map((m) => METHOD_NAMES[m] || m).join(", ")}</td>
                  <td>{o.cashier}</td>
                  <td className="n">{peso(o.total)}</td>
                  <td><span className={`tag ${o.status === "PAID" ? "g" : "r"}`}>{STATUS_NAMES[o.status]}</span></td>
                </tr>
              ))}
              {rows && rows.length === 0 && <tr><td colSpan="7" className="emptyrow">No orders match.</td></tr>}
            </tbody>
          </table>
        )}
        <MoreRows count={(rows || []).length} limit={limit} onMore={() => setLimit(limit + 100)} />
        {rows && rows.length > 0 && <p className="hint">{rows.length} orders &middot; paid total {peso(total)}</p>}
      </div>

      {msg && <p className="notice" role="status">{msg}</p>}
      {open && <OrderDetail id={open} onClose={() => setOpen(null)} onChanged={(m) => { setMsg(m); reload(); }} />}
    </section>
  );
}
