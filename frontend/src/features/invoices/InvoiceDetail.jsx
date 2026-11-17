import { useCallback, useState } from "react";
import { api } from "../../shared/api.js";
import { useLoad } from "../../shared/hooks.js";
import { peso } from "../../shared/money.js";
import { PAY_METHODS, dateOf, invNo, invStatus, poNo, todayManila } from "../../shared/format.js";

/** One invoice. HQ passes canPay to record payments; the branch sees it read-only. */
export default function InvoiceDetail({ id, base, canPay, onClose, onChanged }) {
  const load = useCallback(() => api(`${base}/${id}`), [base, id]);
  const { data: d, error, reload } = useLoad(load);
  const [amount, setAmount] = useState("");
  const [method, setMethod] = useState("BANK");
  const [reference, setReference] = useState("");
  const [paidOn, setPaidOn] = useState(todayManila());
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);

  async function pay(e) {
    e.preventDefault();
    setBusy(true); setErr("");
    try {
      await api(`${base}/${id}/payments`, { method: "POST", body: { amount, method, reference, paidOn } });
      setAmount(""); setReference("");
      onChanged(`Payment recorded for ${invNo(id)}.`);
      await reload();
    } catch (e2) { setErr(e2.message); }
    finally { setBusy(false); }
  }

  const inv = d?.invoice;
  const st = inv && invStatus(inv);

  return (
    <div className="scrim" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal wide" role="dialog" aria-modal="true">
        {error && <p className="err" role="alert">{error}</p>}
        {!d && !error && <p role="status">Loading...</p>}
        {d && (
          <>
            <h2>{invNo(inv.id)} <span className={`tag ${st.tone}`}>{st.label}</span></h2>
            <p className="hint">{inv.branch} &middot; for {poNo(inv.poId)} &middot; issued {dateOf(inv.issuedAt.slice(0, 10))} &middot; due {dateOf(inv.dueDate)}</p>

            <div className="tw">
              <table>
                <thead><tr><th>Item</th><th className="n">Received</th><th className="n">Unit cost</th><th className="n">Amount</th></tr></thead>
                <tbody>
                  {d.lines.map((l) => (
                    <tr key={l.name}><td><b>{l.name}</b> <small>({l.unit})</small></td><td className="n">{l.qty}</td>
                      <td className="n">{peso(l.unitCost)}</td><td className="n">{peso(l.qty * l.unitCost)}</td></tr>
                  ))}
                </tbody>
                <tfoot>
                  <tr><td colSpan="3" className="n">Invoice total</td><td className="n"><b>{peso(inv.amount)}</b></td></tr>
                  <tr><td colSpan="3" className="n">Paid so far</td><td className="n">{peso(inv.paid)}</td></tr>
                  <tr><td colSpan="3" className="n"><b>Balance</b></td><td className="n"><b>{peso(inv.balance)}</b></td></tr>
                </tfoot>
              </table>
            </div>

            <h3>Payments</h3>
            {d.payments.length === 0 ? <p className="hint">No payments yet.</p> : (
              <div className="tw">
                <table>
                  <thead><tr><th>Date</th><th>How</th><th>Reference</th><th>Recorded by</th><th className="n">Amount</th></tr></thead>
                  <tbody>
                    {d.payments.map((p) => (
                      <tr key={p.id}><td>{dateOf(p.paidOn)}</td><td>{PAY_METHODS[p.method]}</td><td>{p.reference}</td><td>{p.recordedBy}</td><td className="n"><b>{peso(p.amount)}</b></td></tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            {canPay && inv.balance > 0 && (
              <form className="stack" style={{ gap: 10 }} onSubmit={pay}>
                <h3>Record a payment</h3>
                <div className="opts">
                  <div className="addr" style={{ flex: 1, minWidth: 130 }}><label htmlFor="pamt">Amount (&#8369;)</label>
                    <input id="pamt" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} placeholder={(inv.balance / 100).toFixed(2)} required /></div>
                  <div className="addr" style={{ flex: 1, minWidth: 130 }}><label htmlFor="pdate">Date paid</label>
                    <input id="pdate" type="date" value={paidOn} max={todayManila()} onChange={(e) => setPaidOn(e.target.value)} required /></div>
                </div>
                <div className="opts" role="group" aria-label="How it was paid">
                  {Object.entries(PAY_METHODS).map(([k, v]) => <button key={k} type="button" className="chip" aria-pressed={method === k} onClick={() => setMethod(k)}>{v}</button>)}
                </div>
                <div className="addr"><label htmlFor="pref">Reference (optional)</label>
                  <input id="pref" value={reference} onChange={(e) => setReference(e.target.value)} placeholder="Bank transaction no., check no..." /></div>
                {err && <p className="err" role="alert">{err}</p>}
                <button className="btn warm" type="submit" disabled={busy}>Record payment</button>
              </form>
            )}
          </>
        )}
        <button className="btn ghost" onClick={onClose}>Close</button>
      </div>
    </div>
  );
}
