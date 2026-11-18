import { useState } from "react";
import { api } from "../../shared/api.js";
import { peso, totals } from "../../shared/money.js";
import { useAuth } from "../../app/AuthContext.jsx";
import { useSync } from "../../app/SyncContext.jsx";
import { makeEntry } from "../../shared/offline.js";
import { cartGross } from "./cart.js";

const METHODS = [["CASH", "Cash"], ["GCASH", "GCash"], ["MAYA", "Maya"], ["CARD", "Card"]];

export default function PayModal({ cart, otype, disc, rule, vatRegistered, idNo, needsPin, orderId, total, onBack, onPaid }) {
  const { me } = useAuth();
  const sync = useSync();
  const [method, setMethod] = useState("CASH");
  const [tender, setTender] = useState(total);
  const [reference, setReference] = useState("");
  const [pin, setPin] = useState("");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");

  const cash = method === "CASH";
  const ok = !cash || tender >= total;
  const quick = [total, Math.ceil(total / 10000) * 10000, Math.ceil(total / 50000) * 50000, Math.ceil(total / 100000) * 100000]
    .filter((v, i, a) => a.indexOf(v) === i);

  async function complete() {
    setBusy(true);
    setErr("");
    // the browser only says WHAT was sold; the server prices it. The id makes a retry safe.
    const body = {
      id: orderId,
      orderType: otype,
      discountType: disc,
      discountIdNo: idNo || null,
      paymentMethod: method,
      tendered: cash ? tender : null,
      reference: reference.trim() || null,
      lines: cart.map((l) => ({ productId: l.productId, variantId: l.variantId, addonIds: l.addonIds, qty: l.qty })),
    };
    try {
      const res = await api("/api/pos/orders", { method: "POST", body: { ...body, managerPin: needsPin && pin ? pin : null }, timeoutMs: 10000 });
      onPaid(res, method, cash ? tender : res.total);
    } catch (e) {
      if (e.status) { setErr(e.message); setBusy(false); return; }
      // No internet. The sale still happens: save it on this device and upload it later. If the first
      // attempt did reach the server, the upload just gets the same order back (same id).
      // the manager PIN is never saved on the device; a discount over its limit is flagged for review when it uploads
      const t = totals(cartGross(cart), rule, vatRegistered);
      const entry = makeEntry({ request: body, by: me.username, expectedTotal: t.pay, receipt: {} });
      if (!sync.enqueue(entry)) {
        setErr("No internet, and this device could not save the order. Do not serve it until it is written down.");
        setBusy(false);
        return;
      }
      onPaid({ offline: true, ref: entry.ref, subtotal: t.gross, discount: t.discount, total: t.pay, vatable: t.vatable, vat: t.vat, change: cash ? tender - t.pay : 0 }, method, cash ? tender : t.pay);
    }
  }

  return (
    <>
      <h2>Charge {peso(total)}</h2>
      <div className="opts">
        {METHODS.map(([k, label]) => (
          <button key={k} className="chip" aria-pressed={method === k} onClick={() => { setMethod(k); setTender(total); setErr(""); }}>{label}</button>
        ))}
      </div>
      {cash ? (
        <div>
          <h3>Cash received</h3>
          <div className="opts">
            {quick.map((v) => (
              <button key={v} className="chip" aria-pressed={v === tender} onClick={() => setTender(v)}>{peso(v)}</button>
            ))}
          </div>
          <div className="tot big" style={{ marginTop: 12 }}><span>Change</span><span>{peso(Math.max(tender - total, 0))}</span></div>
        </div>
      ) : (
        <>
          <p className="hint">{method === "CARD" ? "Run the card on the terminal, then enter the approval code." : "Customer scans the QR. Enter the reference number."}</p>
          <div className="addr">
            <label htmlFor="ref">Reference no.</label>
            <input id="ref" value={reference} onChange={(e) => setReference(e.target.value)} />
          </div>
        </>
      )}
      {needsPin && (
        <div className="addr">
          <label htmlFor="mpin">Manager PIN (this discount is over the limit)</label>
          <input id="mpin" type="password" inputMode="numeric" autoComplete="off" maxLength={12} value={pin} onChange={(e) => setPin(e.target.value)} />
        </div>
      )}
      {err && <p className="err" role="alert">{err}</p>}
      <div className="opts">
        <button className="btn ghost" onClick={onBack} disabled={busy}>Back</button>
        <button className="btn" style={{ flex: 1 }} disabled={!ok || busy} onClick={complete}>{busy ? "Saving..." : "Complete order"}</button>
      </div>
    </>
  );
}
