import { useCallback, useState } from "react";
import { api } from "../../shared/api.js";
import { useLoad } from "../../shared/hooks.js";
import { peso } from "../../shared/money.js";
import { PRESETS, presetRange, daysBetween } from "../../shared/dates.js";
import { DISCOUNT_NAMES, METHOD_NAMES, fmtQty, todayManila } from "../../shared/format.js";
import { pesoCell } from "../../shared/csv.js";
import { Kpi, ExportButton, RangeBar, useReport, State } from "./widgets.jsx";

function SalesTab({ base, range, branchQ }) {
  const r = useReport(`${base}/sales`, range, branchQ);
  const d = r.data;
  if (!d) return <State r={r} />;
  const t = d.totals;
  const peak = Math.max(1, ...d.days.map((x) => x.sales));
  const showLabels = d.days.length <= 31;
  const csv = [["Date", "Orders", "Sales", "Discounts", "VAT included", "Refunded orders", "Refunded amount"],
    ...d.days.map((x) => [x.date, x.orders, pesoCell(x.sales), pesoCell(x.discount), pesoCell(x.vat), x.refunds, pesoCell(x.refunded)]),
    ["Total", t.orders, pesoCell(t.sales), pesoCell(t.discount), pesoCell(t.vat), t.refunds, pesoCell(t.refunded)]];
  return (
    <>
      <div className="kpis">
        <Kpi label="Sales" value={peso(t.sales)} note="paid orders, VAT included" />
        <Kpi label="Orders" value={t.orders} note={`${peso(t.avgOrder)} average`} />
        <Kpi label="Discounts given" value={peso(t.discount)} />
        <Kpi label="VAT collected" value={peso(t.vat)} note={`VATable sales ${peso(t.vatable)}`} />
        <Kpi label="Refunds" value={peso(t.refunded)} note={`${t.refunds} ${t.refunds === 1 ? "order" : "orders"}`} bad={t.refunds > 0} />
      </div>
      <div className="sec">
        <div className="pghead"><h2>Sales by day</h2><ExportButton name="sales_by_day" rows={csv} range={range} /></div>
        <div className="bars" role="img" aria-label="Sales by day">
          {d.days.map((x) => (
            <div key={x.date} className="bar" title={`${x.date}: ${peso(x.sales)} (${x.orders} orders)`}>
              <i style={{ height: `${(x.sales / peak) * 100}%` }} />
              {showLabels && <span>{Number(x.date.slice(8))}</span>}
            </div>
          ))}
        </div>
      </div>
      <div className="two">
        <div className="sec tw">
          <h2>How customers paid</h2>
          <table>
            <thead><tr><th>Method</th><th className="n">Orders</th><th className="n">Amount</th></tr></thead>
            <tbody>
              {d.byPayment.map((p) => <tr key={p.method}><td><b>{METHOD_NAMES[p.method]}</b></td><td className="n">{p.orders}</td><td className="n">{peso(p.amount)}</td></tr>)}
              {d.byPayment.length === 0 && <tr><td colSpan="3" className="emptyrow">No sales in this range.</td></tr>}
            </tbody>
          </table>
        </div>
        <div className="sec tw">
          <h2>By cashier</h2>
          <table>
            <thead><tr><th>Cashier</th><th className="n">Orders</th><th className="n">Sales</th><th className="n">Refunds</th></tr></thead>
            <tbody>
              {d.byCashier.map((c) => <tr key={c.cashier}><td><b>{c.cashier}</b></td><td className="n">{c.orders}</td><td className="n">{peso(c.sales)}</td><td className={`n${c.refunds > 0 ? " short" : ""}`}>{c.refunds}</td></tr>)}
              {d.byCashier.length === 0 && <tr><td colSpan="4" className="emptyrow">No sales in this range.</td></tr>}
            </tbody>
          </table>
        </div>
        <div className="sec tw">
          <h2>Discounts</h2>
          <table>
            <thead><tr><th>Type</th><th className="n">Orders</th><th className="n">Given</th></tr></thead>
            <tbody>
              {d.discounts.map((x) => <tr key={x.type}><td><b>{DISCOUNT_NAMES[x.type]}</b></td><td className="n">{x.orders}</td><td className="n">{peso(x.amount)}</td></tr>)}
              {d.discounts.length === 0 && <tr><td colSpan="3" className="emptyrow">No discounts given.</td></tr>}
            </tbody>
          </table>
        </div>
      </div>
    </>
  );
}

function ProductsTab({ base, range, branchQ }) {
  const r = useReport(`${base}/products`, range, branchQ);
  if (!r.data) return <State r={r} />;
  const rows = r.data;
  const total = rows.reduce((s, p) => s + p.sales, 0);
  const csv = [["Product", "Units sold", "Sales (before order discounts)"], ...rows.map((p) => [p.name, p.qty, pesoCell(p.sales)])];
  return (
    <div className="sec tw">
      <div className="pghead"><h2>Products by units sold</h2><ExportButton name="products" rows={csv} range={range} /></div>
      <table>
        <thead><tr><th>Product</th><th className="n">Sold</th><th className="n">Sales</th><th className="n">Share</th></tr></thead>
        <tbody>
          {rows.map((p) => <tr key={p.name}><td><b>{p.name}</b></td><td className="n">{p.qty}</td><td className="n">{peso(p.sales)}</td><td className="n">{total ? `${Math.round((p.sales / total) * 100)}%` : ""}</td></tr>)}
          {rows.length === 0 && <tr><td colSpan="4" className="emptyrow">No sales in this range.</td></tr>}
        </tbody>
      </table>
      <p className="hint">Sales are item prices before any order-level discount, so they can be a little higher than the Sales tab.</p>
    </div>
  );
}

function StockTab({ base, range, branchQ, needsBranch }) {
  const path = `${base}/stock`;
  const load = useCallback(() => (needsBranch ? Promise.resolve([]) : api(`${path}?from=${range.from}&to=${range.to}${branchQ}`)), [needsBranch, path, range.from, range.to, branchQ]);
  const r = useLoad(load);
  if (needsBranch) return <p className="notice" role="status">Pick a branch above to see its stock.</p>;
  if (!r.data) return <State r={r} />;
  const rows = r.data;
  const value = rows.reduce((s, x) => s + x.value, 0);
  const csv = [["Item", "Unit", "Received", "Used in sales", "Wasted", "Count corrections", "On hand now", "Value"],
    ...rows.map((x) => [x.name, x.unit, fmtQty(x.received), fmtQty(x.used), fmtQty(x.wasted), fmtQty(x.adjusted), fmtQty(x.onHand), pesoCell(x.value)])];
  return (
    <div className="sec tw">
      <div className="pghead"><h2>Stock movement</h2><ExportButton name="stock" rows={csv} range={range} /></div>
      <table>
        <thead><tr><th>Item</th><th className="n">Received</th><th className="n">Used in sales</th><th className="n">Wasted</th><th className="n">Corrections</th><th className="n">On hand now</th><th className="n">Value</th></tr></thead>
        <tbody>
          {rows.map((x) => (
            <tr key={x.itemId}><td><b>{x.name}</b> <small>({x.unit})</small></td><td className="n">{fmtQty(x.received)}</td><td className="n">{fmtQty(x.used)}</td>
              <td className="n">{fmtQty(x.wasted)}</td><td className="n">{fmtQty(x.adjusted)}</td>
              <td className={`n${x.onHand < 0 ? " short" : ""}`}><b>{fmtQty(x.onHand)}</b></td><td className="n">{peso(x.value)}</td></tr>
          ))}
          {rows.length === 0 && <tr><td colSpan="7" className="emptyrow">No items yet.</td></tr>}
        </tbody>
        <tfoot><tr><td colSpan="6" className="n">Stock on hand, at cost</td><td className="n"><b>{peso(value)}</b></td></tr></tfoot>
      </table>
    </div>
  );
}

function BranchesTab({ range }) {
  const r = useReport("/api/admin/reports/branches", range);
  if (!r.data) return <State r={r} />;
  const rows = r.data;
  const csv = [["Branch", "Orders", "Sales", "Average order", "Refunded orders", "Refunded amount"],
    ...rows.map((b) => [b.branch, b.orders, pesoCell(b.sales), pesoCell(b.avgOrder), b.refunds, pesoCell(b.refunded)])];
  return (
    <div className="sec tw">
      <div className="pghead"><h2>Branches side by side</h2><ExportButton name="branches" rows={csv} range={range} /></div>
      <table>
        <thead><tr><th>Branch</th><th className="n">Orders</th><th className="n">Sales</th><th className="n">Average order</th><th className="n">Refunds</th></tr></thead>
        <tbody>
          {rows.map((b) => <tr key={b.branchId}><td><b>{b.branch}</b></td><td className="n">{b.orders}</td><td className="n">{peso(b.sales)}</td><td className="n">{peso(b.avgOrder)}</td><td className={`n${b.refunds > 0 ? " short" : ""}`}>{b.refunds}</td></tr>)}
          {rows.length === 0 && <tr><td colSpan="5" className="emptyrow">No open branches.</td></tr>}
        </tbody>
      </table>
    </div>
  );
}

function AgingTab() {
  const load = useCallback(() => api("/api/admin/reports/aging"), []);
  const r = useLoad(load);
  if (!r.data) return <State r={r} />;
  const rows = r.data;
  const sum = (k) => rows.reduce((s, x) => s + x[k], 0);
  const csv = [["Branch", "Not yet due", "1-30 days late", "31-60 days late", "Over 60 days late", "Total owed"],
    ...rows.map((x) => [x.branch, pesoCell(x.current), pesoCell(x.d1to30), pesoCell(x.d31to60), pesoCell(x.d61plus), pesoCell(x.total)])];
  return (
    <div className="sec tw">
      <div className="pghead"><h2>Who owes HQ, and how late (as of today)</h2><ExportButton name="receivables_aging" rows={csv} /></div>
      <table>
        <thead><tr><th>Branch</th><th className="n">Not yet due</th><th className="n">1-30 days late</th><th className="n">31-60 days late</th><th className="n">Over 60 days</th><th className="n">Total</th></tr></thead>
        <tbody>
          {rows.map((x) => (
            <tr key={x.branchId}><td><b>{x.branch}</b></td><td className="n">{peso(x.current)}</td><td className="n">{peso(x.d1to30)}</td>
              <td className={`n${x.d31to60 > 0 ? " short" : ""}`}>{peso(x.d31to60)}</td><td className={`n${x.d61plus > 0 ? " short" : ""}`}>{peso(x.d61plus)}</td><td className="n"><b>{peso(x.total)}</b></td></tr>
          ))}
          {rows.length === 0 && <tr><td colSpan="6" className="emptyrow">Nobody owes anything.</td></tr>}
        </tbody>
        {rows.length > 0 && <tfoot><tr><td><b>Total</b></td><td className="n">{peso(sum("current"))}</td><td className="n">{peso(sum("d1to30"))}</td><td className="n">{peso(sum("d31to60"))}</td><td className="n">{peso(sum("d61plus"))}</td><td className="n"><b>{peso(sum("total"))}</b></td></tr></tfoot>}
      </table>
    </div>
  );
}

/** side="branch": the user's own branch. side="hq": all branches, with a branch picker. */
export default function ReportsPage({ side }) {
  const hq = side === "hq";
  const [range, setRange] = useState(() => presetRange("7d", todayManila()));
  const [tab, setTab] = useState("sales");
  const [branchId, setBranchId] = useState("");
  const loadBranches = useCallback(() => (hq ? api("/api/admin/branches") : Promise.resolve([])), [hq]);
  const branches = useLoad(loadBranches);

  const base = hq ? "/api/admin/reports" : "/api/branch/reports";
  const branchQ = hq && branchId ? `&branchId=${branchId}` : "";
  const tabs = hq
    ? [["sales", "Sales"], ["products", "Products"], ["branches", "Branches"], ["stock", "Stock"], ["aging", "Receivables aging"]]
    : [["sales", "Sales"], ["products", "Products"], ["stock", "Stock"]];
  const needsRange = tab !== "aging";
  const tooLong = daysBetween(range.from, range.to) > 366;

  return (
    <section>
      <header className="pghead"><div><h1>Reports</h1><p>{hq ? "Sales, stock and money owed across the chain." : "How this branch is doing."} Sales count paid orders only.</p></div></header>
      <div className="tabs" role="tablist" aria-label="Report">
        {tabs.map(([k, label]) => <button key={k} role="tab" aria-selected={tab === k} className="chip" aria-pressed={tab === k} onClick={() => setTab(k)}>{label}</button>)}
      </div>
      <div className="filters">
        {needsRange && <RangeBar range={range} setRange={setRange} />}
        {hq && ["sales", "products", "stock"].includes(tab) && (
          <label className="datepick">Branch
            <select value={branchId} onChange={(e) => setBranchId(e.target.value)}>
              <option value="">{tab === "stock" ? "Pick a branch" : "All branches"}</option>
              {(branches.data || []).map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
            </select>
          </label>
        )}
      </div>
      {tooLong && <p className="notice" role="alert">Pick a range of one year or less.</p>}
      {!tooLong && tab === "sales" && <SalesTab base={base} range={range} branchQ={branchQ} />}
      {!tooLong && tab === "products" && <ProductsTab base={base} range={range} branchQ={branchQ} />}
      {!tooLong && tab === "stock" && <StockTab base={base} range={range} branchQ={branchQ} needsBranch={hq && !branchId} />}
      {!tooLong && tab === "branches" && <BranchesTab range={range} />}
      {tab === "aging" && <AgingTab />}
    </section>
  );
}
