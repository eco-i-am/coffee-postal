import { peso } from "../../shared/money.js";

const TYPE_NAMES = { DINE_IN: "Dine-in", TAKEOUT: "Takeout", DELIVERY: "Delivery" };

export default function Receipt({ res, method, tendered, cart, otype, discName, idNo, vatRegistered = true, onDone }) {
  const time = new Date().toLocaleTimeString("en-PH", { hour: "numeric", minute: "2-digit" });
  return (
    <>
      <h2>{res.offline ? `Paid. Saved as ${res.ref}` : `Order #${res.orderNo} paid`}</h2>
      {res.offline && <p className="notice offline" role="status">No internet. This order is saved on this device and gets its order number when it uploads.</p>}
      <div className="receipt printable">
        <div className="c"><b>COFFEE POSTAL</b><br />{time} &middot; {TYPE_NAMES[otype]}<hr /></div>
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
        <div className="c"><hr />Thank you! Your affordable freshly brewed coffee destination.<br /><i>Not an official receipt.</i></div>
      </div>
      <div className="opts noprint">
        <button className="btn ghost" onClick={() => window.print()}>Print</button>
        <button className="btn warm" style={{ flex: 1 }} onClick={onDone}>New order</button>
      </div>
    </>
  );
}
