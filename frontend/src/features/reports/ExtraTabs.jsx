import { peso } from "../../shared/money.js";
import { METHOD_NAMES, PAY_METHODS, TYPE_NAMES, dateOf, dateTimeOf, fmtQty, poNo, invNo } from "../../shared/format.js";
import { pesoCell } from "../../shared/csv.js";
import { Kpi, ExportButton, useReport, State } from "./widgets.jsx";

const pct = (part, whole) => (whole ? `${Math.round((part / whole) * 100)}%` : "");
export const hoursOf = (min) => `${Math.floor(min / 60)}h ${String(min % 60).padStart(2, "0")}m`;
const signed = (c) => (c === 0 ? "Exact" : `${c > 0 ? "+" : "-"}${peso(Math.abs(c))}`);

/** How customers paid: money taken by method (paid and refunded), and per day. */
export function PaymentsTab({ base, range, branchQ }) {
  const r = useReport(`${base}/payments`, range, branchQ);
  if (!r.data) return <State r={r} />;
  const { byMethod, days } = r.data;
  const methods = [...new Set(byMethod.map((x) => x.method))];
  const get = (m, s) => byMethod.find((x) => x.method === m && x.status === s) || { orders: 0, amount: 0 };
  const paidTotal = methods.reduce((s, m) => s + get(m, "PAID").amount, 0);
  const refundTotal = methods.reduce((s, m) => s + get(m, "REFUNDED").amount, 0);
  const dates = [...new Set(days.map((d) => d.date))];
  const cell = (d, m) => days.find((x) => x.date === d && x.method === m)?.amount || 0;
  const csv = [["Method", "Paid orders", "Collected", "Share", "Refunded orders", "Refunded"],
    ...methods.map((m) => [METHOD_NAMES[m] || m, get(m, "PAID").orders, pesoCell(get(m, "PAID").amount), pct(get(m, "PAID").amount, paidTotal), get(m, "REFUNDED").orders, pesoCell(get(m, "REFUNDED").amount)]),
    ["Total", "", pesoCell(paidTotal), "", "", pesoCell(refundTotal)]];
  const csvDays = [["Date", ...methods.map((m) => METHOD_NAMES[m] || m), "Total"],
    ...dates.map((d) => [d, ...methods.map((m) => pesoCell(cell(d, m))), pesoCell(methods.reduce((s, m) => s + cell(d, m), 0))])];
  return (
    <>
      <div className="kpis">
        <Kpi label="Collected" value={peso(paidTotal)} note="paid orders" />
        <Kpi label="Refunded" value={peso(refundTotal)} bad={refundTotal > 0} />
        {methods.map((m) => <Kpi key={m} label={METHOD_NAMES[m] || m} value={peso(get(m, "PAID").amount)} note={pct(get(m, "PAID").amount, paidTotal)} />)}
      </div>
      <div className="sec tw">
        <div className="pghead"><h2>By payment method</h2><ExportButton name="payments_by_method" rows={csv} range={range} /></div>
        <table>
          <thead><tr><th>Method</th><th className="n">Paid orders</th><th className="n">Collected</th><th className="n">Share</th><th className="n">Refunded orders</th><th className="n">Refunded</th></tr></thead>
          <tbody>
            {methods.map((m) => (
              <tr key={m}><td><b>{METHOD_NAMES[m] || m}</b></td><td className="n">{get(m, "PAID").orders}</td><td className="n">{peso(get(m, "PAID").amount)}</td>
                <td className="n">{pct(get(m, "PAID").amount, paidTotal)}</td><td className="n">{get(m, "REFUNDED").orders}</td>
                <td className={`n${get(m, "REFUNDED").amount > 0 ? " short" : ""}`}>{peso(get(m, "REFUNDED").amount)}</td></tr>
            ))}
            {methods.length === 0 && <tr><td colSpan="6" className="emptyrow">No payments in this range.</td></tr>}
          </tbody>
        </table>
      </div>
      {dates.length > 0 && (
        <div className="sec tw">
          <div className="pghead"><h2>By day</h2><ExportButton name="payments_by_day" rows={csvDays} range={range} /></div>
          <table>
            <thead><tr><th>Date</th>{methods.map((m) => <th key={m} className="n">{METHOD_NAMES[m] || m}</th>)}<th className="n">Total</th></tr></thead>
            <tbody>
              {dates.map((d) => (
                <tr key={d}><td>{dateOf(d)}</td>{methods.map((m) => <td key={m} className="n">{peso(cell(d, m))}</td>)}
                  <td className="n"><b>{peso(methods.reduce((s, m) => s + cell(d, m), 0))}</b></td></tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}

/** Every discount given, with who approved it. */
export function DiscountsTab({ base, range, branchQ, hq }) {
  const r = useReport(`${base}/discounts`, range, branchQ);
  if (!r.data) return <State r={r} />;
  const { summary, rows } = r.data;
  const total = summary.reduce((s, x) => s + x.amount, 0);
  const count = summary.reduce((s, x) => s + x.orders, 0);
  const review = rows.filter((x) => x.needsReview).length;
  const csv = [["Order", "Date", ...(hq ? ["Branch"] : []), "Cashier", "Discount", "ID number", "Subtotal", "Discount amount", "Total", "Approved by", "Needs review", "Status"],
    ...rows.map((x) => [x.orderNo, dateTimeOf(x.at), ...(hq ? [x.branch] : []), x.cashier, x.discount, x.idNo || "", pesoCell(x.subtotal), pesoCell(x.amount), pesoCell(x.total),
      x.approvedBy || "", x.needsReview ? "Yes" : "", x.status])];
  return (
    <>
      <div className="kpis">
        <Kpi label="Discounts given" value={peso(total)} note="paid orders" />
        <Kpi label="Orders with a discount" value={count} />
        <Kpi label="Waiting for review" value={review} note="over the limit, sold offline" bad={review > 0} />
      </div>
      <div className="sec tw">
        <h2>By discount</h2>
        <table>
          <thead><tr><th>Discount</th><th className="n">Orders</th><th className="n">Amount</th><th className="n">Share</th></tr></thead>
          <tbody>
            {summary.map((x) => <tr key={x.discount}><td><b>{x.discount}</b></td><td className="n">{x.orders}</td><td className="n">{peso(x.amount)}</td><td className="n">{pct(x.amount, total)}</td></tr>)}
            {summary.length === 0 && <tr><td colSpan="4" className="emptyrow">No discounts in this range.</td></tr>}
          </tbody>
        </table>
      </div>
      <div className="sec tw">
        <div className="pghead"><h2>Every discounted order</h2><ExportButton name="discounts" rows={csv} range={range} /></div>
        <table>
          <thead><tr><th>Order</th><th>When</th>{hq && <th>Branch</th>}<th>Cashier</th><th>Discount</th><th>ID no.</th><th className="n">Discount</th><th className="n">Total</th><th>Approved by</th><th>Status</th></tr></thead>
          <tbody>
            {rows.map((x) => (
              <tr key={`${x.branch}-${x.orderNo}`}><td><b>#{x.orderNo}</b></td><td>{dateTimeOf(x.at)}</td>{hq && <td>{x.branch}</td>}<td>{x.cashier}</td><td>{x.discount}</td><td>{x.idNo || ""}</td>
                <td className="n">{peso(x.amount)}</td><td className="n">{peso(x.total)}</td>
                <td>{x.approvedBy || ""}{x.needsReview && <span className="tag y">Review</span>}</td><td>{x.status === "PAID" ? "Paid" : "Refunded"}</td></tr>
            ))}
            {rows.length === 0 && <tr><td colSpan={hq ? 10 : 9} className="emptyrow">No discounts in this range.</td></tr>}
          </tbody>
        </table>
        {rows.length >= 1000 && <p className="hint">Showing the newest 1,000. Pick a shorter range to see the rest.</p>}
      </div>
    </>
  );
}

/** Dine-in, takeout and delivery. */
export function OrderTypesTab({ base, range, branchQ }) {
  const r = useReport(`${base}/order-types`, range, branchQ);
  if (!r.data) return <State r={r} />;
  const rows = r.data;
  const sales = rows.reduce((s, x) => s + x.sales, 0);
  const orders = rows.reduce((s, x) => s + x.orders, 0);
  const csv = [["Order type", "Orders", "Sales", "Share of sales", "Average order", "Refunded orders", "Refunded"],
    ...rows.map((x) => [TYPE_NAMES[x.type], x.orders, pesoCell(x.sales), pct(x.sales, sales), pesoCell(x.avgOrder), x.refunds, pesoCell(x.refunded)])];
  return (
    <>
      <div className="kpis">
        {rows.map((x) => <Kpi key={x.type} label={TYPE_NAMES[x.type]} value={peso(x.sales)} note={`${x.orders} orders · ${pct(x.sales, sales) || "0%"}`} />)}
      </div>
      <div className="sec tw">
        <div className="pghead"><h2>Sales by order type</h2><ExportButton name="order_types" rows={csv} range={range} /></div>
        <table>
          <thead><tr><th>Order type</th><th className="n">Orders</th><th className="n">Sales</th><th className="n">Share</th><th className="n">Average order</th><th className="n">Refunds</th></tr></thead>
          <tbody>
            {rows.map((x) => (
              <tr key={x.type}><td><b>{TYPE_NAMES[x.type]}</b></td><td className="n">{x.orders}</td><td className="n">{peso(x.sales)}</td><td className="n">{pct(x.sales, sales)}</td>
                <td className="n">{peso(x.avgOrder)}</td><td className={`n${x.refunds > 0 ? " short" : ""}`}>{x.refunds}</td></tr>
            ))}
          </tbody>
          <tfoot><tr><td><b>Total</b></td><td className="n">{orders}</td><td className="n"><b>{peso(sales)}</b></td><td></td><td></td><td></td></tr></tfoot>
        </table>
      </div>
    </>
  );
}

/** Per cashier: sales, refunds, discounts and how their drawer counts came out. */
export function CashiersTab({ base, range, branchQ }) {
  const r = useReport(`${base}/cashiers`, range, branchQ);
  if (!r.data) return <State r={r} />;
  const rows = r.data;
  const csv = [["Cashier", "Orders", "Sales", "Average order", "Discounts", "Refunded orders", "Refunded", "Shifts closed", "Shifts not exact", "Drawer over/short"],
    ...rows.map((x) => [x.name, x.orders, pesoCell(x.sales), pesoCell(x.avgOrder), pesoCell(x.discount), x.refunds, pesoCell(x.refunded), x.shifts, x.offShifts, pesoCell(x.overShort)])];
  return (
    <div className="sec tw">
      <div className="pghead"><h2>Cashiers</h2><ExportButton name="cashiers" rows={csv} range={range} /></div>
      <table>
        <thead><tr><th>Cashier</th><th className="n">Orders</th><th className="n">Sales</th><th className="n">Average</th><th className="n">Discounts</th><th className="n">Refunds</th><th className="n">Shifts closed</th><th className="n">Drawer over / short</th></tr></thead>
        <tbody>
          {rows.map((x) => (
            <tr key={x.id}><td><b>{x.name}</b></td><td className="n">{x.orders}</td><td className="n">{peso(x.sales)}</td><td className="n">{peso(x.avgOrder)}</td><td className="n">{peso(x.discount)}</td>
              <td className={`n${x.refunds > 0 ? " short" : ""}`}>{x.refunds > 0 ? `${x.refunds} (${peso(x.refunded)})` : 0}</td>
              <td className="n">{x.shifts}{x.offShifts > 0 ? ` (${x.offShifts} off)` : ""}</td>
              <td className={`n${x.overShort < 0 ? " short" : ""}`}>{x.shifts ? signed(x.overShort) : ""}</td></tr>
          ))}
          {rows.length === 0 && <tr><td colSpan="8" className="emptyrow">No sales in this range.</td></tr>}
        </tbody>
      </table>
      <p className="hint">Over / short adds up counted cash minus expected cash for every shift the cashier closed in this range. A minus is cash missing.</p>
    </div>
  );
}

/** Attendance from the time clock. */
export function EmployeesTab({ base, range, branchQ, hq }) {
  const r = useReport(`${base}/employees`, range, branchQ);
  if (!r.data) return <State r={r} />;
  const rows = r.data;
  const csv = [["Employee", "Position", ...(hq ? ["Branch"] : []), "Days worked", "Shifts", "Hours", "Not clocked out", "Flagged punches", "Photos to check"],
    ...rows.map((x) => [x.name, x.position, ...(hq ? [x.branch] : []), x.days, x.shifts, (x.minutes / 60).toFixed(2), x.notClockedOut, x.flagged, x.photoReview])];
  return (
    <div className="sec tw">
      <div className="pghead"><h2>Attendance</h2><ExportButton name="employee_attendance" rows={csv} range={range} /></div>
      <table>
        <thead><tr><th>Employee</th>{hq && <th>Branch</th>}<th className="n">Days</th><th className="n">Shifts</th><th className="n">Hours</th><th className="n">Not clocked out</th><th className="n">Flagged</th><th className="n">Photos to check</th></tr></thead>
        <tbody>
          {rows.map((x) => (
            <tr key={x.id}><td><b>{x.name}</b>{x.position && <><br /><small>{x.position}</small></>}</td>{hq && <td>{x.branch}</td>}
              <td className="n">{x.days}</td><td className="n">{x.shifts}</td><td className="n">{hoursOf(x.minutes)}</td>
              <td className={`n${x.notClockedOut > 0 ? " short" : ""}`}>{x.notClockedOut}</td><td className="n">{x.flagged}</td><td className={`n${x.photoReview > 0 ? " short" : ""}`}>{x.photoReview}</td></tr>
          ))}
          {rows.length === 0 && <tr><td colSpan={hq ? 8 : 7} className="emptyrow">No time in or out in this range.</td></tr>}
        </tbody>
      </table>
      <p className="hint">Hours add up each time in to the next time out. A shift with no time out is not counted in the hours: fix it under Employees. Flagged = the tablet clock was wrong or a manager typed the punch in.</p>
    </div>
  );
}

/** Items bought from outside suppliers. */
export function OutsourceTab({ base, range, branchQ, hq }) {
  const r = useReport(`${base}/outsource`, range, branchQ);
  if (!r.data) return <State r={r} />;
  const rows = r.data;
  const total = rows.reduce((s, x) => s + x.amount, 0);
  const csv = [["Date", ...(hq ? ["Branch"] : []), "Vendor", "Receipt no.", "Item", "Stocked item", "Quantity", "Unit cost", "Amount", "Paid with"],
    ...rows.map((x) => [x.date, ...(hq ? [x.branch] : []), x.vendor, x.receiptNo || "", x.description, x.stocked ? "Yes" : "No", fmtQty(x.qty), pesoCell(x.unitCost), pesoCell(x.amount), PAY_METHODS[x.paidWith] || x.paidWith])];
  return (
    <>
      <div className="kpis"><Kpi label="Bought outside" value={peso(total)} note={`${rows.length} ${rows.length === 1 ? "line" : "lines"}`} /></div>
      <div className="sec tw">
        <div className="pghead"><h2>Outsource purchases</h2><ExportButton name="outsource" rows={csv} range={range} /></div>
        <table>
          <thead><tr><th>Date</th>{hq && <th>Branch</th>}<th>Vendor</th><th>Item</th><th className="n">Qty</th><th className="n">Unit cost</th><th className="n">Amount</th><th>Paid with</th></tr></thead>
          <tbody>
            {rows.map((x, i) => (
              <tr key={i}><td>{dateOf(x.date)}</td>{hq && <td>{x.branch}</td>}<td>{x.vendor}{x.receiptNo && <><br /><small>Receipt {x.receiptNo}</small></>}</td>
                <td>{x.description}{x.stocked && <span className="tag g">In stock</span>}</td><td className="n">{fmtQty(x.qty)}</td><td className="n">{peso(x.unitCost)}</td>
                <td className="n">{peso(x.amount)}</td><td>{PAY_METHODS[x.paidWith] || x.paidWith}</td></tr>
            ))}
            {rows.length === 0 && <tr><td colSpan={hq ? 8 : 7} className="emptyrow">Nothing bought outside in this range.</td></tr>}
          </tbody>
          {rows.length > 0 && <tfoot><tr><td colSpan={hq ? 6 : 5}><b>Total</b></td><td className="n"><b>{peso(total)}</b></td><td></td></tr></tfoot>}
        </table>
        {rows.length >= 1000 && <p className="hint">Showing the newest 1,000 lines. Pick a shorter range to see the rest.</p>}
      </div>
    </>
  );
}

/** What branches paid HQ against their purchase order invoices. */
export function PoPaymentsTab({ base, range, branchQ, hq }) {
  const r = useReport(`${base}/po-payments`, range, branchQ);
  if (!r.data) return <State r={r} />;
  const rows = r.data;
  const total = rows.reduce((s, x) => s + x.amount, 0);
  const byMethod = Object.entries(rows.reduce((m, x) => ({ ...m, [x.method]: (m[x.method] || 0) + x.amount }), {}));
  const csv = [["Date paid", ...(hq ? ["Branch"] : []), "Purchase order", "Invoice", "Method", "Reference", "Amount", "Recorded by"],
    ...rows.map((x) => [x.paidOn, ...(hq ? [x.branch] : []), poNo(x.poId), invNo(x.invoiceId), PAY_METHODS[x.method] || x.method, x.reference || "", pesoCell(x.amount), x.recordedBy])];
  return (
    <>
      <div className="kpis">
        <Kpi label="Paid for purchase orders" value={peso(total)} note={`${rows.length} ${rows.length === 1 ? "payment" : "payments"}`} />
        {byMethod.map(([m, amt]) => <Kpi key={m} label={PAY_METHODS[m] || m} value={peso(amt)} />)}
      </div>
      <div className="sec tw">
        <div className="pghead"><h2>Purchase order payments</h2><ExportButton name="po_payments" rows={csv} range={range} /></div>
        <table>
          <thead><tr><th>Paid on</th>{hq && <th>Branch</th>}<th>Purchase order</th><th>Invoice</th><th>Method</th><th>Reference</th><th className="n">Amount</th><th>Recorded by</th></tr></thead>
          <tbody>
            {rows.map((x, i) => (
              <tr key={i}><td>{dateOf(x.paidOn)}</td>{hq && <td>{x.branch}</td>}<td>{poNo(x.poId)}</td><td>{invNo(x.invoiceId)}</td><td>{PAY_METHODS[x.method] || x.method}</td>
                <td>{x.reference || ""}</td><td className="n"><b>{peso(x.amount)}</b></td><td>{x.recordedBy}</td></tr>
            ))}
            {rows.length === 0 && <tr><td colSpan={hq ? 8 : 7} className="emptyrow">No payments in this range.</td></tr>}
          </tbody>
        </table>
        {rows.length >= 1000 && <p className="hint">Showing the newest 1,000. Pick a shorter range to see the rest.</p>}
      </div>
    </>
  );
}
