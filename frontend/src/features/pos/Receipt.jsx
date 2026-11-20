import { useState } from "react";
import { api } from "../../shared/api.js";
import { useAuth } from "../../app/AuthContext.jsx";
import { useReceiptText } from "../../shared/receiptText.js";
import QrCode, { rateUrl, useRateToken } from "../../shared/QrCode.jsx";
import { peso } from "../../shared/money.js";

const TYPE_NAMES = { DINE_IN: "Dine-in", TAKEOUT: "Takeout", DELIVERY: "Delivery" };

/** Optional: the customer's rating, entered by the cashier. Never in the way: a failure is quietly ignored and nothing is printed. */
function Rate({ orderNo }) {
  const [rating, setRating] = useState(0);
  const [comment, setComment] = useState("");
  const [state, setState] = useState("idle");   // idle | saving | saved | failed
  async function save() {
    setState("saving");
    try {
      await api("/api/pos/feedback", { method: "POST", body: { rating, comment, orderRef: orderNo ? String(orderNo) : null } });
      setState("saved");
    } catch { setState("failed"); }
  }
  if (state === "saved") return <p className="hint noprint" role="status">Thank you. Feedback saved.</p>;
  return (
    <div className="rate noprint">
      <b>Customer feedback (optional)</b>
      <div className="ratestars" role="group" aria-label="Rating">
        {[1, 2, 3, 4, 5].map((n) => (
          <button key={n} type="button" className={n <= rating ? "on" : ""} aria-label={`${n} ${n === 1 ? "star" : "stars"}`} aria-pressed={n === rating} onClick={() => setRating(n)}>★</button>
        ))}
      </div>
      {rating > 0 && (
        <div className="ratebox">
          <input aria-label="Comment" maxLength={500} value={comment} onChange={(e) => setComment(e.target.value)} placeholder="Comment (optional)" />
          <button type="button" className="btn ghost sm" disabled={state === "saving"} onClick={save}>Save</button>
        </div>
      )}
      {state === "failed" && <small className="err" role="alert">Could not save the feedback. You can carry on.</small>}
    </div>
  );
}

export default function Receipt({ res, method, tendered, cart, otype, discName, idNo, vatRegistered = true, who, onDone }) {
  const { can } = useAuth();
  const rt = useReceiptText();
  const rateToken = useRateToken();
  const time = new Date().toLocaleTimeString("en-PH", { hour: "numeric", minute: "2-digit" });
  return (
    <>
      <h2>{res.offline ? `Paid. Saved as ${res.ref}` : `Order #${res.orderNo} paid`}</h2>
      {res.offline && <p className="notice offline" role="status">No internet. This order is saved on this device and gets its order number when it uploads.</p>}
      <div className="receipt printable">
        <div className="c"><b>{rt.companyName}</b><br />{time} &middot; {TYPE_NAMES[otype]}{who && <><br />For: <b>{who}</b></>}<hr /></div>
        {cart.map((l) => (
          <div key={l.key}>
            <div className="r"><span>{l.qty} x {l.name}</span><span>{peso(l.unit * l.qty)}</span></div>
            {l.opt && <div className="optline">{l.opt}</div>}
          </div>
        ))}
        <hr />
        <div className="r"><span>Subtotal</span><span>{peso(res.subtotal)}</span></div>
        {res.discount > 0 && <div className="r"><span>{discName || "Discount"}</span><span>-{peso(res.discount)}</span></div>}
        {res.discount > 0 && idNo && <div className="optline">ID: {idNo}</div>}
        <div className="r"><b>TOTAL</b><b>{peso(res.total)}</b></div>
        <div className="r"><span>{method}</span><span>{peso(tendered)}</span></div>
        {method === "CASH" && <div className="r"><span>Change</span><span>{peso(res.change)}</span></div>}
        <hr />
        {vatRegistered && <div className="r"><span>VATable</span><span>{peso(res.vatable)}</span></div>}
        {vatRegistered && <div className="r"><span>VAT 12%</span><span>{peso(res.vat)}</span></div>}
        <div className="c"><hr />{rt.footer}<br /><i>{rt.note}</i></div>
        {rateToken && <div className="c rateqr"><hr />Scan to rate your visit<QrCode text={rateUrl(rateToken)} size={96} label="Scan to rate your visit" /></div>}
      </div>
      {can("feedback_add") && !res.offline && <Rate orderNo={res.orderNo} />}
      <div className="opts noprint">
        <button className="btn ghost" onClick={() => window.print()}>Print</button>
        <button className="btn warm" style={{ flex: 1 }} onClick={onDone}>New order</button>
      </div>
    </>
  );
}
