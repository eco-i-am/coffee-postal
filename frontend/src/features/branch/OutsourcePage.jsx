import { useCallback, useState } from "react";
import { api } from "../../shared/api.js";
import { useLoad } from "../../shared/hooks.js";
import { useAuth } from "../../app/AuthContext.jsx";
import { peso } from "../../shared/money.js";
import { presetRange } from "../../shared/dates.js";
import { dateOf, todayManila } from "../../shared/format.js";
import { pesoCell } from "../../shared/csv.js";
import { ExportButton, RangeBar } from "../reports/widgets.jsx";
import PagedRows from "../../shared/PagedRows.jsx";

const METHODS = ["CASH", "GCASH", "MAYA", "BANK", "CHECK"];
const EMPTY_LINE = { itemId: "", description: "", qty: "", unitCost: "" };

/** Things this branch bought locally, outside the HQ order flow. Stocked items add to the branch's stock. */
export default function OutsourcePage() {
  const { can } = useAuth();
  const [range, setRange] = useState(() => presetRange("30d", todayManila()));
  const load = useCallback(() => api(`/api/branch/outsource?from=${range.from}&to=${range.to}`), [range.from, range.to]);
  const { data, error, loading, reload } = useLoad(load);
  const stock = useLoad(useCallback(() => api("/api/branch/inventory"), [])).data || [];

  const [form, setForm] = useState({ vendor: "", receiptNo: "", purchasedOn: todayManila(), paidWith: "CASH", note: "" });
  const [lines, setLines] = useState([{ ...EMPTY_LINE }]);
  const [msg, setMsg] = useState("");
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });
  const setLine = (n, patch) => setLines(lines.map((l, k) => (k === n ? { ...l, ...patch } : l)));

  const rows = data || [];
  const total = rows.reduce((s, p) => s + p.total, 0);

  async function save(e) {
    e.preventDefault(); setErr(""); setMsg("");
    const body = {
      ...form,
      lines: lines.filter((l) => l.qty || l.unitCost || l.itemId || l.description).map((l) => ({
        itemId: l.itemId ? Number(l.itemId) : null, description: l.description, qty: l.qty, unitCost: l.unitCost,
      })),
    };
    setBusy(true);
    try {
      await api("/api/branch/outsource", { method: "POST", body });
      setMsg("Saved. Stocked items were added to your stock.");
      setForm({ ...form, vendor: "", receiptNo: "", note: "" });
      setLines([{ ...EMPTY_LINE }]);
      reload();
    } catch (e2) { setErr(e2.message); } finally { setBusy(false); }
  }

  const exportRows = [
    ["Date", "Vendor", "Receipt", "Paid with", "Item", "Qty", "Cost each", "Amount"],
    ...rows.flatMap((p) => p.lines.map((l) => [p.purchasedOn, p.vendor, p.receiptNo || "", p.paidWith, l.description, l.qty / 1000, pesoCell(l.unitCost), pesoCell(l.amount)])),
  ];

  return (
    <section>
      <header className="pghead"><div><h1>Outsource</h1><p>Things you bought locally instead of ordering from HQ. Keep the receipt.</p></div></header>
      {msg && <p className="notice" role="status">{msg}</p>}
      {can("outsource_add") && (
        <div className="sec">
          <h2>Record a purchase</h2>
          <form onSubmit={save} className="stack" noValidate>
            <div className="opts">
              <div className="addr" style={{ flex: 2, minWidth: 160 }}><label htmlFor="ov">Bought from</label><input id="ov" value={form.vendor} onChange={set("vendor")} placeholder="Palengke, SM Hypermarket..." required /></div>
              <div className="addr" style={{ flex: 1, minWidth: 110 }}><label htmlFor="orc">Receipt no.</label><input id="orc" value={form.receiptNo} onChange={set("receiptNo")} /></div>
              <div className="addr" style={{ flex: 1, minWidth: 130 }}><label htmlFor="od">Date</label><input id="od" type="date" max={todayManila()} value={form.purchasedOn} onChange={set("purchasedOn")} /></div>
              <div className="addr" style={{ flex: 1, minWidth: 100 }}><label htmlFor="op">Paid with</label>
                <select className="line" id="op" value={form.paidWith} onChange={set("paidWith")}>{METHODS.map((m) => <option key={m}>{m}</option>)}</select></div>
            </div>
            {lines.map((l, n) => (
              <div className="opts" key={n}>
                <select className="line" aria-label="Stocked item (optional)" value={l.itemId} onChange={(e) => setLine(n, { itemId: e.target.value })} style={{ flex: 2, minWidth: 150 }}>
                  <option value="">Not a stocked item</option>
                  {stock.map((s) => <option key={s.itemId} value={s.itemId}>{s.name} ({s.unit})</option>)}
                </select>
                {!l.itemId && <input aria-label="What was bought" value={l.description} placeholder="What was bought" onChange={(e) => setLine(n, { description: e.target.value })} style={{ flex: 2, minWidth: 130 }} />}
                <input aria-label="Quantity" value={l.qty} inputMode="decimal" placeholder="Qty" onChange={(e) => setLine(n, { qty: e.target.value })} style={{ width: 80 }} />
                <input aria-label="Cost each" value={l.unitCost} inputMode="decimal" placeholder="Cost each" onChange={(e) => setLine(n, { unitCost: e.target.value })} style={{ width: 100 }} />
                {lines.length > 1 && <button type="button" className="btn ghost sm" onClick={() => setLines(lines.filter((_, k) => k !== n))}>Remove</button>}
              </div>
            ))}
            <button type="button" className="btn ghost sm" onClick={() => setLines([...lines, { ...EMPTY_LINE }])}>Add another line</button>
            <div className="addr"><label htmlFor="on">Note (optional)</label><input id="on" value={form.note} maxLength={200} onChange={set("note")} /></div>
            {err && <p className="err" role="alert">{err}</p>}
            <button className="btn warm" type="submit" disabled={busy}>Save purchase</button>
          </form>
        </div>
      )}

      <RangeBar range={range} setRange={setRange} />
      {error && <p className="err" role="alert">{error}</p>}
      <div className="sec tw">
        <div className="opts"><h2 style={{ flex: 1 }}>Purchases</h2><ExportButton name="outsource" rows={exportRows} range={range} /></div>
        {loading && !data ? <p role="status">Loading...</p> : (
          <table>
            <thead><tr><th>Date</th><th>Bought from</th><th>What</th><th>Paid with</th><th className="n">Total</th></tr></thead>
            <tbody>
              <PagedRows rows={rows} render={(p) => (
                <tr key={p.id}>
                  <td>{dateOf(p.purchasedOn)}</td><td><b>{p.vendor}</b>{p.receiptNo && <small> #{p.receiptNo}</small>}</td>
                  <td>{p.lines.map((l) => <div key={l.id}>{l.qty / 1000} × {l.description}</div>)}</td>
                  <td>{p.paidWith}</td><td className="n">{peso(p.total)}</td>
                </tr>
              )} />
              {rows.length === 0 && <tr><td colSpan="5" className="emptyrow">No outsource purchases in this range.</td></tr>}
            </tbody>
            <tfoot><tr><td colSpan="4" className="n">Total</td><td className="n"><b>{peso(total)}</b></td></tr></tfoot>
          </table>
        )}
      </div>
    </section>
  );
}
