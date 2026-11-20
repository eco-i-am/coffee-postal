import { useCallback, useState } from "react";
import { api } from "../../shared/api.js";
import { useLoad } from "../../shared/hooks.js";
import MoreRows from "../../shared/MoreRows.jsx";
import { peso } from "../../shared/money.js";
import { dateOf, invNo, invStatus } from "../../shared/format.js";
import { useAuth } from "../../app/AuthContext.jsx";
import InvoiceDetail from "./InvoiceDetail.jsx";
import PagedRows from "../../shared/PagedRows.jsx";

const FILTERS = [["open", "Still owed"], ["overdue", "Overdue"], ["paid", "Paid"], ["all", "All"]];

function Kpi({ label, value, note, tone }) {
  return <div className="kpi"><small>{label}</small><b className={tone === "bad" ? "short" : ""}>{value}</b>{note && <span>{note}</span>}</div>;
}

/** Shared list for HQ receivables (side="hq") and branch payables (side="branch"). */
export default function InvoiceList({ side }) {
  const { can } = useAuth();
  const hq = side === "hq";
  const base = hq ? "/api/admin/receivables" : "/api/branch/payables";
  const [filter, setFilter] = useState("open");
  const [limit, setLimit] = useState(100);
  const loadRows = useCallback(() => api(`${base}?filter=${filter}&limit=${limit}`), [base, filter, limit]);
  const loadSum = useCallback(() => api(`${base}/summary`), [base]);
  const { data: rows, error, loading, reload } = useLoad(loadRows);
  const sum = useLoad(loadSum);
  const [open, setOpen] = useState(null);
  const [msg, setMsg] = useState("");

  const s = sum.data;
  return (
    <section>
      <header className="pghead"><div>
        <h1>{hq ? "Receivables" : "Payables"}</h1>
        <p>{hq ? "What branches owe HQ for purchase orders, and the payments received." : "What this branch owes HQ for the supplies it received."}</p>
      </div></header>

      {s && (
        <div className="kpis">
          <Kpi label={hq ? "Owed to HQ" : "You owe"} value={peso(s.outstanding)} note={`${s.openCount} open ${s.openCount === 1 ? "invoice" : "invoices"}`} />
          <Kpi label="Overdue" value={peso(s.overdue)} note={`${s.overdueCount} ${s.overdueCount === 1 ? "invoice" : "invoices"}`} tone={s.overdue > 0 ? "bad" : ""} />
        </div>
      )}

      {hq && s && s.byBranch.length > 0 && (
        <div className="sec tw">
          <h2>Who owes the most</h2>
          <table>
            <thead><tr><th>Branch</th><th className="n">Open invoices</th><th className="n">Owes</th><th className="n">Of which overdue</th></tr></thead>
            <tbody>
              {s.byBranch.map((b) => (
                <tr key={b.branchId}><td><b>{b.branch}</b></td><td className="n">{b.open}</td><td className="n">{peso(b.outstanding)}</td>
                  <td className={`n${b.overdue > 0 ? " short" : ""}`}>{b.overdue > 0 ? peso(b.overdue) : "-"}</td></tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <div className="tabs" role="group" aria-label="Filter">
        {FILTERS.map(([k, label]) => <button key={k} className="chip" aria-pressed={filter === k} onClick={() => setFilter(k)}>{label}</button>)}
      </div>
      {msg && <p className="notice" role="status">{msg}</p>}
      {error && <p className="notice" role="alert">{error}</p>}
      <div className="sec tw">
        {loading && !rows ? <p role="status">Loading...</p> : (
          <table>
            <thead><tr><th>No.</th>{hq && <th>Branch</th>}<th>Due</th><th className="n">Amount</th><th className="n">Balance</th><th>Status</th></tr></thead>
            <tbody>
              <PagedRows rows={rows || []} render={(r) => {
                const st = invStatus(r);
                return (
                  <tr key={r.id} className="clickrow" tabIndex={0} onClick={() => setOpen(r.id)} onKeyDown={(e) => e.key === "Enter" && setOpen(r.id)}>
                    <td><b>{invNo(r.id)}</b></td>{hq && <td>{r.branch}</td>}<td>{dateOf(r.dueDate)}</td>
                    <td className="n">{peso(r.amount)}</td><td className="n"><b>{peso(r.balance)}</b></td>
                    <td><span className={`tag ${st.tone}`}>{st.label}</span></td>
                  </tr>
                );
              }} />
              {rows && rows.length === 0 && <tr><td colSpan={hq ? 6 : 5} className="emptyrow">{filter === "open" ? "Nothing owed. All clear." : "Nothing here."}</td></tr>}
            </tbody>
          </table>
        )}
        <MoreRows count={(rows || []).length} limit={limit} onMore={() => setLimit(limit + 100)} />
      </div>
      {open && <InvoiceDetail id={open} base={base} canPay={hq && can("receivables_add")} onClose={() => setOpen(null)}
        onChanged={(m) => { setMsg(m); reload(); sum.reload(); }} />}
    </section>
  );
}
