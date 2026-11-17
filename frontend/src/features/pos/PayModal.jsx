import { useState } from "react";
import { api } from "../../shared/api.js";
import { peso } from "../../shared/money.js";

const METHODS = [["CASH", "Cash"], ["GCASH", "GCash"], ["MAYA", "Maya"], ["CARD", "Card"]];

export default function PayModal({ cart, otype, disc, orderId, total, onBack, onPaid }) {
  const [method, setMethod] = useState("CASH");
  const [tender, setTender] = useState(total);
  const [reference, setReference] = useState("");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");

  const cash = method === "CASH";
  const ok = !cash || tender >= total;
  const quick = [total, Math.ceil(total / 10000) * 10000, Math.ceil(total / 50000) * 50000, Math.ceil(total / 100000) * 100000]
    .filter((v, i, a) => a.indexOf(v) === i);

  async function complete() {
    setBusy(true);
    setErr("");
    try {
      // the browser only says WHAT was sold; the server prices it. The id makes a retry safe.
      const res = await api("/api/pos/orders", {
        method: "POST",
        body: {
          id: orderId,
          orderType: otype,
          discountType: disc,
          paymentMethod: method,
          tendered: cash ? tender : null,
          reference: reference.trim() || null,
          lines: cart.map((l) => ({ productId: l.productId, variantId: l.variantId, addonIds: l.addonIds, qty: l.qty })),
        },
      });
      onPaid(res, method, cash ? tender : res.total);
    } catch (e) {
      setErr(e.status ? e.message : "Could not reach the server. Your order is still here. Try again.");
      setBusy(false);
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
      {err && <p className="err" role="alert">{err}</p>}
      <div className="opts">
        <button className="btn ghost" onClick={onBack} disabled={busy}>Back</button>
        <button className="btn" style={{ flex: 1 }} disabled={!ok || busy} onClick={complete}>{busy ? "Saving..." : "Complete order"}</button>
      </div>
    </>
  );
}
