import { useCallback, useState } from "react";
import { api } from "../../shared/api.js";
import { useLoad } from "../../shared/hooks.js";
import { useAuth } from "../../app/AuthContext.jsx";
import { peso } from "../../shared/money.js";
import { presetRange } from "../../shared/dates.js";
import { dateOf, todayManila } from "../../shared/format.js";
import { pesoCell } from "../../shared/csv.js";
import { ExportButton, RangeBar } from "../reports/widgets.jsx";
import Modal from "../../shared/Modal.jsx";

const METHODS = ["CASH", "BANK", "GCASH", "MAYA", "CHECK"];

/** Expenses. A branch records its own; HQ records its own (or for a branch) and sees all. A wrong entry is voided, never deleted. */
export default function ExpensesPage({ side }) {
  const hq = side === "hq";
  const { can } = useAuth();
  const perm = hq ? "accounting" : "branch_expenses";
  const base = hq ? "/api/admin" : "/api/branch";
  const [range, setRange] = useState(() => presetRange("month", todayManila()));
  const [branchId, setBranchId] = useState("");
  const load = useCallback(() => api(`${base}/expenses?from=${range.from}&to=${range.to}${hq && branchId ? `&branchId=${branchId}` : ""}`), [base, hq, range.from, range.to, branchId]);
  const { data, error, loading, reload } = useLoad(load);
  const cats = useLoad(useCallback(() => api(`${base}/expense-categories`), [base])).data || [];
  const branches = useLoad(useCallback(() => (hq ? api("/api/admin/branches") : Promise.resolve([])), [hq])).data || [];

  const [form, setForm] = useState({ categoryId: "", amount: "", date: todayManila(), description: "", paidWith: "CASH", receiptNo: "", branchId: "" });
  const [msg, setMsg] = useState("");
  const [err, setErr] = useState("");
  const [voiding, setVoiding] = useState(null);
  const [adding, setAdding] = useState(false);
  const [reason, setReason] = useState("");
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });

  const rows = data || [];
  const live = rows.filter((x) => !x.voided);
  const total = live.reduce((s, x) => s + x.amount, 0);
  const byCat = Object.entries(live.reduce((m, x) => ({ ...m, [x.category]: (m[x.category] || 0) + x.amount }), {})).sort((a, b) => b[1] - a[1]);

  async function add(e) {
    e.preventDefault(); setErr(""); setMsg("");
    try {
      const body = { ...form, categoryId: Number(form.categoryId) || null, branchId: hq && form.branchId ? Number(form.branchId) : null };
      await api(`${base}/expenses`, { method: "POST", body });
      setMsg("Expense saved.");
      setAdding(false);
      setForm({ ...form, amount: "", description: "", receiptNo: "" });
      reload();
    } catch (e2) { setErr(e2.message); }
  }

  async function doVoid(e) {
    e.preventDefault();
    try {
      await api(`${base}/expenses/${voiding.id}/void`, { method: "POST", body: { reason } });
      setMsg("Expense voided.");
      setVoiding(null); setReason("");
      reload();
    } catch (e2) { setMsg(e2.message); setVoiding(null); }
  }

  const exportRows = [
    ["Date", "Branch", "Category", "Description", "Paid with", "Receipt", "Amount", "Status"],
    ...rows.map((x) => [x.date, x.branch, x.category, x.description, x.paidWith, x.receiptNo || "", pesoCell(x.amount), x.voided ? `Voided: ${x.voidReason}` : "OK"]),
  ];

  return (
    <section className="fitpage">
      <header className="pghead">
        <div><h1>Expenses</h1><p>{hq ? "What the chain spent, by branch and category." : "What this branch spent. Keep the receipt."}</p></div>
        <div className="toolbar">
          <ExportButton name="expenses" rows={exportRows} range={range} />
          {can(`${perm}_add`) && <button className="btn warm sm" type="button" onClick={() => { setErr(""); setMsg(""); setAdding(true); }}>+ Record expense</button>}
        </div>
      </header>
      <div className="ctl">
        <RangeBar range={range} setRange={setRange} />
        {hq && (
          <label className="datepick">Branch
            <select value={branchId} onChange={(e) => setBranchId(e.target.value)}><option value="">All (including head office)</option>
              {branches.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}</select></label>
        )}
      </div>
      {msg && !adding && <p className="notice" role="status">{msg}</p>}
      {error && <p className="notice" role="alert">{error}</p>}

      <div className="chips" aria-label="Spending by category">
        <span className="tag y"><b>Total {peso(total)}</b></span>
        {byCat.map(([c, a]) => <span className="tag" key={c}>{c}: <b>{peso(a)}</b></span>)}
        {byCat.length === 0 && <small className="hint">Nothing recorded in this range.</small>}
      </div>

      <div className="sec fill">
        {loading && !data ? <p role="status">Loading...</p> : (
          <div className="tw">
            <table className="dense">
              <thead><tr><th>Date</th>{hq && <th>Branch</th>}<th>Category</th><th>What for</th><th>Paid with</th><th className="n">Amount</th><th></th></tr></thead>
              <tbody>
                {rows.map((x) => (
                  <tr key={x.id} className={x.voided ? "voided" : ""}>
                    <td>{dateOf(x.date)}</td>{hq && <td>{x.branch}</td>}<td>{x.category}</td>
                    <td>{x.description}{x.receiptNo && <small> #{x.receiptNo}</small>}{x.voided && <small> · Voided: {x.voidReason}</small>}</td>
                    <td>{x.paidWith}</td><td className="n">{x.voided ? <s>{peso(x.amount)}</s> : peso(x.amount)}</td>
                    <td className="rowbtns">{!x.voided && can(`${perm}_edit`) && <button className="btn ghost sm" type="button" onClick={() => { setVoiding(x); setReason(""); }}>Void</button>}</td>
                  </tr>
                ))}
                {rows.length === 0 && <tr><td colSpan={hq ? 7 : 6} className="emptyrow">No expenses in this range.</td></tr>}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {adding && (
        <Modal title="Record an expense" onClose={() => setAdding(false)} onSubmit={add}>
          <div className="grid2">
            <div className="addr"><label htmlFor="xc">Category</label>
              <select className="line" id="xc" value={form.categoryId} onChange={set("categoryId")} required>
                <option value="">Pick a category</option>{cats.filter((c) => c.active).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}</select></div>
            <div className="addr"><label htmlFor="xa">Amount (&#8369;)</label><input id="xa" value={form.amount} inputMode="decimal" onChange={set("amount")} required /></div>
            <div className="addr"><label htmlFor="xd">Date</label><input id="xd" type="date" max={todayManila()} value={form.date} onChange={set("date")} /></div>
            <div className="addr"><label htmlFor="xp">Paid with</label>
              <select className="line" id="xp" value={form.paidWith} onChange={set("paidWith")}>{METHODS.map((m) => <option key={m}>{m}</option>)}</select></div>
            <div className="addr"><label htmlFor="xr">Receipt no.</label><input id="xr" value={form.receiptNo} onChange={set("receiptNo")} /></div>
            {hq && (
              <div className="addr"><label htmlFor="xb">For</label>
                <select className="line" id="xb" value={form.branchId} onChange={set("branchId")}>
                  <option value="">Head office</option>{branches.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}</select></div>
            )}
          </div>
          <div className="addr"><label htmlFor="xs">What for</label><input id="xs" value={form.description} maxLength={200} onChange={set("description")} required /></div>
          {err && <p className="err" role="alert">{err}</p>}
          <div className="opts">
            <button type="button" className="btn ghost" onClick={() => setAdding(false)}>Cancel</button>
            <button className="btn warm" type="submit" style={{ flex: 1 }}>Save expense</button>
          </div>
        </Modal>
      )}

      {voiding && (
        <Modal title="Void this expense?" onClose={() => setVoiding(null)} onSubmit={doVoid}>
          <p className="hint">{voiding.description}, {peso(voiding.amount)}. It stays in the list, crossed out, and no longer counts.</p>
          <div className="addr"><label htmlFor="vr">Why</label><input id="vr" value={reason} maxLength={200} onChange={(e) => setReason(e.target.value)} autoFocus required /></div>
          <div className="opts"><button type="button" className="btn ghost" onClick={() => setVoiding(null)}>Cancel</button><button className="btn warm" style={{ flex: 1 }} type="submit" disabled={!reason.trim()}>Void expense</button></div>
        </Modal>
      )}
    </section>
  );
}
