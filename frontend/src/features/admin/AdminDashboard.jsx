import { useCallback } from "react";
import { Link } from "react-router-dom";
import { api } from "../../shared/api.js";
import { useLoad } from "../../shared/hooks.js";
import { peso } from "../../shared/money.js";
import { prettyDate } from "../../shared/format.js";

function Kpi({ label, value, note, bad }) {
  return <div className="kpi"><small>{label}</small><b className={bad ? "short" : ""}>{value}</b>{note && <span>{note}</span>}</div>;
}

export default function AdminDashboard() {
  const load = useCallback(() => api("/api/admin/dashboard"), []);
  const { data: d, error, loading } = useLoad(load);
  return (
    <section>
      <header className="pghead"><div><h1>HQ Dashboard</h1><p>{d ? prettyDate(d.date) : "Today across all branches"}</p></div></header>
      {error && <p className="notice" role="alert">{error}</p>}
      {loading && !d && <p role="status">Loading...</p>}
      {d && (
        <>
          <div className="kpis">
            <Kpi label="Sales today" value={peso(d.sales)} note={`${d.orders} orders, all branches`} />
            <Kpi label="Owed by branches" value={peso(d.receivables.outstanding)} note={`${d.receivables.openCount} open invoices`} />
            <Kpi label="Overdue" value={peso(d.receivables.overdue)} note={`${d.receivables.overdueCount} invoices`} bad={d.receivables.overdue > 0} />
            <Kpi label="Orders for review" value={d.poToReview} note={`${d.poToSend} approved, to send`} />
          </div>
          <div className="two">
            <div className="sec tw">
              <h2>Branch sales today</h2>
              <table>
                <thead><tr><th>Branch</th><th className="n">Orders</th><th className="n">Sales</th></tr></thead>
                <tbody>
                  {d.byBranch.map((b) => <tr key={b.branchId}><td><b>{b.branch}</b></td><td className="n">{b.orders}</td><td className="n">{peso(b.sales)}</td></tr>)}
                  {d.byBranch.length === 0 && <tr><td colSpan="3" className="emptyrow">No open branches yet. <Link to="/admin/branches">Add one</Link></td></tr>}
                </tbody>
              </table>
            </div>
            <div className="sec tw">
              <h2>Needs your attention</h2>
              <ul className="todo">
                <li><Link to="/admin/branch-po">{d.poToReview} purchase {d.poToReview === 1 ? "order" : "orders"} waiting for review</Link></li>
                <li><Link to="/admin/branch-po">{d.poToSend} approved {d.poToSend === 1 ? "order" : "orders"} to send</Link></li>
                <li><Link to="/admin/receivables">{d.receivables.overdueCount} overdue {d.receivables.overdueCount === 1 ? "invoice" : "invoices"}</Link></li>
              </ul>
            </div>
          </div>
        </>
      )}
    </section>
  );
}
