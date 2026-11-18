import { peso } from "../../shared/money.js";
import { dateTimeOf } from "../../shared/format.js";

const TYPE_NAMES = { DINE_IN: "Dine-in", TAKEOUT: "Takeout", DELIVERY: "Delivery" };
const METHOD_NAMES = { CASH: "Cash", GCASH: "GCash", MAYA: "Maya", CARD: "Card" };

/** A receipt printed from the saved order (a reprint). Prints on an 80 mm roll; see the print rules in app.css. */
export default function ReceiptSheet({ r, onClose }) {
  return (
    <>
      <div className="receipt printable">
        <div className="c">
          <b>COFFEE POSTAL</b><br />
          {r.branch}<br />
          {r.address && <>{r.address}<br /></>}
          {r.phone && <>{r.phone}<br /></>}
          {r.vatRegistered && r.tin && <>VAT REG TIN {r.tin}<br /></>}
          <b>REPRINT &middot; COPY {r.reprintCount}</b><hr />
        </div>
        <div className="r"><span>Order #{r.orderNo}</span><span>{TYPE_NAMES[r.orderType]}</span></div>
        <div className="r"><span>{dateTimeOf(r.at)}</span><span>{r.cashier}</span></div>
        {r.status !== "PAID" && <div className="c"><b>{r.status === "REFUNDED" ? "REFUNDED" : r.status}</b></div>}
        <hr />
        {r.lines.map((l, i) => (
          <div key={i}>
            <div className="r"><span>{l.qty} x {l.name}</span><span>{peso(l.unitPrice * l.qty)}</span></div>
            {(l.variant || l.addons) && <div className="optline">{[l.variant, l.addons].filter(Boolean).join(", ")}</div>}
          </div>
        ))}
        <hr />
        <div className="r"><span>Subtotal</span><span>{peso(r.subtotal)}</span></div>
        {r.discount > 0 && <div className="r"><span>{r.discountName || "Discount"}</span><span>-{peso(r.discount)}</span></div>}
        {r.discount > 0 && r.discountIdNo && <div className="optline">ID: {r.discountIdNo}</div>}
        <div className="r"><b>TOTAL</b><b>{peso(r.total)}</b></div>
        <div className="r"><span>{METHOD_NAMES[r.method] || r.method}</span><span>{peso(r.tendered)}</span></div>
        {r.method === "CASH" && <div className="r"><span>Change</span><span>{peso(r.change)}</span></div>}
        {r.reference && <div className="optline">Ref {r.reference}</div>}
        {r.vatRegistered && (
          <>
            <hr />
            <div className="r"><span>VATable</span><span>{peso(r.vatable)}</span></div>
            <div className="r"><span>VAT 12%</span><span>{peso(r.vat)}</span></div>
          </>
        )}
        <div className="c"><hr />Thank you!<br /><i>Not an official receipt.</i></div>
      </div>
      <div className="opts noprint">
        <button type="button" className="btn ghost" onClick={onClose}>Close</button>
        <button type="button" className="btn warm" style={{ flex: 1 }} onClick={() => window.print()}>Print</button>
      </div>
    </>
  );
}
