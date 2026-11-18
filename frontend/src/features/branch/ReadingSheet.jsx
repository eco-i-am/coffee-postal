import { peso } from "../../shared/money.js";
import { dateTimeOf, METHOD_NAMES } from "../../shared/format.js";

/**
 * X reading (open shift) or Z reading (closed shift) of one cashier's shift.
 * Gross is before discounts; net is what customers paid. Refunded orders are not in net.
 */
export default function ReadingSheet({ r, onClose }) {
  const z = r.kind === "Z";
  const row = (label, value, bold) => <div className="r">{bold ? <b>{label}</b> : <span>{label}</span>}{bold ? <b>{value}</b> : <span>{value}</span>}</div>;
  return (
    <>
      <div className="receipt printable">
        <div className="c">
          <b>COFFEE POSTAL</b><br />{r.branch}<br />
          <b>{z ? `Z READING #${r.zNo}` : "X READING"}</b><br />
          {!z && <i>Shift still open. Nothing is closed by this report.</i>}
          <hr />
        </div>
        {row("Cashier", r.cashier)}
        {row("Opened", dateTimeOf(r.openedAt))}
        {z && row("Closed", dateTimeOf(r.closedAt))}
        {row("Orders", r.orders)}
        {r.firstOrderNo != null && row("Order no.", `${r.firstOrderNo} to ${r.lastOrderNo}`)}
        <hr />
        {row("Gross sales", peso(r.gross))}
        {row("Discounts", `-${peso(r.discounts)}`)}
        {r.discountLines.map((d) => <div className="optline" key={d.label}>{d.label} ({d.count}): {peso(d.amount)}</div>)}
        {row("NET SALES", peso(r.net), true)}
        {r.vatRegistered && row("VATable", peso(r.vatable))}
        {r.vatRegistered && row("VAT 12%", peso(r.vat))}
        {row(`Refunded (${r.refundCount})`, peso(r.refunded))}
        <hr />
        <div className="c"><b>PAYMENTS</b></div>
        {r.payments.map((p) => row(`${METHOD_NAMES[p.label] || p.label} (${p.count})`, peso(p.amount)))}
        {r.payments.length === 0 && <div className="c">None</div>}
        <hr />
        <div className="c"><b>CASH DRAWER</b></div>
        {row("Opening cash", peso(r.openingCash))}
        {row("Cash sales", peso(r.cashSales))}
        {row("Cash in", peso(r.cashIn))}
        {row("Cash out", `-${peso(r.cashOut)}`)}
        {row(z ? "Expected" : "Should be in drawer", peso(r.expected), true)}
        {z && r.counted != null && row("Counted", peso(r.counted))}
        {z && r.variance != null && row(r.variance === 0 ? "Exact" : r.variance < 0 ? "Short" : "Over", peso(Math.abs(r.variance)), true)}
        <div className="c"><hr />{z ? "End of Z reading" : "End of X reading"}</div>
      </div>
      <div className="opts noprint">
        <button type="button" className="btn ghost" onClick={onClose}>Close</button>
        <button type="button" className="btn warm" style={{ flex: 1 }} onClick={() => window.print()}>Print</button>
      </div>
    </>
  );
}
