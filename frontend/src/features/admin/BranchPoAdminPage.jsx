import { useCallback, useState } from "react";
import { api } from "../../shared/api.js";
import { useLoad } from "../../shared/hooks.js";
import MoreRows from "../../shared/MoreRows.jsx";
import { peso } from "../../shared/money.js";
import { PO_STATUS, dateTimeOf, poNo, poTone } from "../../shared/format.js";
import PoDetail from "../po/PoDetail.jsx";

import { PO_STAGES } from "../../shared/format.js";

const FILTERS = [["REQUESTED", "Needs review"], ...PO_STAGES.slice(1).map((s) => [s, PO_STATUS[s]]), ["REJECTED", "Rejected"], ["", "All"]];

export default function BranchPoAdminPage() {
  const [status, setStatus] = useState("REQUESTED");
  const [limit, setLimit] = useState(100);
  const load = useCallback(() => api(`/api/admin/branch-pos?limit=${limit}${status ? `&status=${status}` : ""}`), [status, limit]);
  const { data: rows, error, loading, reload } = useLoad(load);
  const [open, setOpen] = useState(null);
  const [msg, setMsg] = useState("");

  return (
    <section>
      <header className="pghead">
        <div><h1>Branch PO</h1><p>Review what branches ask for, approve it, and move it along: pay, process, pick-up, transit, delivered.</p></div>
      </header>
      <div className="tabs" role="group" aria-label="Filter">
        {FILTERS.map(([k, label]) => <button key={k} className="chip" aria-pressed={status === k} onClick={() => setStatus(k)}>{label}</button>)}
      </div>
      {msg && <p className="notice" role="status">{msg}</p>}
      {error && <p className="notice" role="alert">{error}</p>}
      <div className="sec tw">
        {loading && !rows ? <p role="status">Loading...</p> : (
          <table>
            <thead><tr><th>No.</th><th>Branch</th><th>Asked</th><th className="n">Items</th><th className="n">Value</th><th>Status</th></tr></thead>
            <tbody>
              {(rows || []).map((r) => (
                <tr key={r.id} className="clickrow" tabIndex={0} onClick={() => setOpen(r.id)} onKeyDown={(e) => e.key === "Enter" && setOpen(r.id)}>
                  <td><b>{poNo(r.id)}</b></td><td>{r.branch}</td><td>{dateTimeOf(r.requestedAt)}</td>
                  <td className="n">{r.lines}</td><td className="n">{peso(r.value)}</td>
                  <td><span className={`tag ${poTone(r.status)}`}>{PO_STATUS[r.status]}</span></td>
                </tr>
              ))}
              {rows && rows.length === 0 && <tr><td colSpan="6" className="emptyrow">{status === "REQUESTED" ? "Nothing waiting for review." : "Nothing here."}</td></tr>}
            </tbody>
          </table>
        )}
        <MoreRows count={(rows || []).length} limit={limit} onMore={() => setLimit(limit + 100)} />
      </div>
      {open && <PoDetail id={open} side="hq" onClose={() => setOpen(null)} onChanged={(m) => { setMsg(m); reload(); }} />}
    </section>
  );
}
