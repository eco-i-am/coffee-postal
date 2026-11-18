import { useCallback, useState } from "react";
import { api } from "../../shared/api.js";
import { useLoad } from "../../shared/hooks.js";
import { useAuth } from "../../app/AuthContext.jsx";
import { peso } from "../../shared/money.js";
import { dateTimeOf, todayManila } from "../../shared/format.js";

const TONE = { ORDERED: ["y", "Ordered"], PARTIAL: ["y", "Partly received"], RECEIVED: ["g", "Received"], CANCELLED: ["r", "Cancelled"] };
const METHODS = ["BANK", "CASH", "GCASH", "MAYA", "CHECK"];
const q = (t) => String(t / 1000);

function Modal({ title, onClose, children }) {
  return (
    <div className="scrim" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal" role="dialog" aria-modal="true" aria-label={title}><h2>{title}</h2>{children}</div>
    </div>
  );
}

/** HQ buying: who we buy from, what we ordered, what arrived, and what we still owe. */
export default function SuppliersPage() {
  const { can } = useAuth();
  const [tab, setTab] = useState("orders");
  const suppliers = useLoad(useCallback(() => api("/api/admin/suppliers"), []));
  return (
    <section>
      <header className="pghead"><div><h1>Suppliers</h1><p>Who we buy from, what we ordered, what arrived, and what we still owe.</p></div></header>
      <div className="filters"><div className="tabs" role="group" aria-label="Section">
        <button className="chip" aria-pressed={tab === "orders"} onClick={() => setTab("orders")}>Purchase orders</button>
        <button className="chip" aria-pressed={tab === "suppliers"} onClick={() => setTab("suppliers")}>Suppliers</button>
      </div></div>
      {tab === "suppliers"
        ? <SupplierList can={can} suppliers={suppliers} />
        : <OrderList can={can} suppliers={suppliers.data || []} onChange={suppliers.reload} />}
    </section>
  );
}

function SupplierList({ can, suppliers }) {
  const { data, error, reload } = suppliers;
  const [form, setForm] = useState({ name: "", contact: "", phone: "", email: "", address: "", termsDays: "0" });
  const [msg, setMsg] = useState("");
  const [fields, setFields] = useState({});
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });

  async function add(e) {
    e.preventDefault(); setMsg(""); setFields({});
    try {
      await api("/api/admin/suppliers", { method: "POST", body: { ...form, termsDays: Number(form.termsDays) || 0 } });
      setMsg(`${form.name.trim()} added.`);
      setForm({ name: "", contact: "", phone: "", email: "", address: "", termsDays: "0" });
      reload();
    } catch (err) { setFields(err.fields || {}); setMsg(err.message); }
  }
  async function toggle(s) {
    try {
      await api(`/api/admin/suppliers/${s.id}`, { method: "POST", body: { ...s, termsDays: s.termsDays, active: !s.active } });
      reload();
    } catch (err) { setMsg(err.message); }
  }

  return (
    <>
      {msg && <p className="notice" role="status">{msg}</p>}
      {error && <p className="err" role="alert">{error}</p>}
      <div className="two">
        {can("suppliers_add") && (
          <div className="sec">
            <h2>Add a supplier</h2>
            <form onSubmit={add} className="stack" noValidate>
              <div className="addr"><label htmlFor="sn">Name</label><input id="sn" value={form.name} onChange={set("name")} required />{fields.name && <p className="err" role="alert">{fields.name}</p>}</div>
              <div className="addr"><label htmlFor="sc">Contact person</label><input id="sc" value={form.contact} onChange={set("contact")} /></div>
              <div className="opts">
                <div className="addr" style={{ flex: 1 }}><label htmlFor="sp">Phone</label><input id="sp" value={form.phone} onChange={set("phone")} /></div>
                <div className="addr" style={{ flex: 1 }}><label htmlFor="se">Email</label><input id="se" value={form.email} onChange={set("email")} /></div>
              </div>
              <div className="addr"><label htmlFor="sa">Address</label><input id="sa" value={form.address} onChange={set("address")} /></div>
              <div className="addr"><label htmlFor="st">Payment terms (days)</label><input id="st" value={form.termsDays} inputMode="numeric" onChange={set("termsDays")} /></div>
              <button className="btn warm" type="submit">Add supplier</button>
            </form>
          </div>
        )}
        <div className="sec tw">
          <h2>All suppliers</h2>
          <table>
            <thead><tr><th>Supplier</th><th>Contact</th><th>Terms</th><th className="n">We owe</th><th>Status</th><th></th></tr></thead>
            <tbody>
              {(data || []).map((s) => (
                <tr key={s.id}>
                  <td><b>{s.name}</b></td><td>{[s.contact, s.phone].filter(Boolean).join(", ")}</td><td>{s.termsDays} days</td>
                  <td className="n">{peso(s.owed)}</td>
                  <td><span className={`tag ${s.active ? "g" : "r"}`}>{s.active ? "Active" : "Hidden"}</span></td>
                  <td className="rowbtns">{can("suppliers_edit") && <button className="btn ghost sm" type="button" onClick={() => toggle(s)}>{s.active ? "Hide" : "Show"}</button>}</td>
                </tr>
              ))}
              {data && data.length === 0 && <tr><td colSpan="6" className="emptyrow">No suppliers yet.</td></tr>}
            </tbody>
          </table>
        </div>
      </div>
    </>
  );
}

function OrderList({ can, suppliers, onChange }) {
  const { data, error, loading, reload } = useLoad(useCallback(() => api("/api/admin/supplier-pos"), []));
  const items = useLoad(useCallback(() => api("/api/admin/items"), [])).data || [];
  const branches = useLoad(useCallback(() => api("/api/admin/branches"), [])).data || [];
  const [open, setOpen] = useState(null);     // PO being looked at
  const [making, setMaking] = useState(false);
  const [msg, setMsg] = useState("");

  async function look(id) { try { setOpen(await api(`/api/admin/supplier-pos/${id}`)); } catch (err) { setMsg(err.message); } }
  const refresh = async (id) => { reload(); onChange(); if (id) await look(id); };

  return (
    <>
      {msg && <p className="notice" role="status">{msg}</p>}
      {error && <p className="err" role="alert">{error}</p>}
      <div className="sec tw">
        <div className="opts"><h2 style={{ flex: 1 }}>Purchase orders</h2>
          {can("supplier_po_add") && <button className="btn warm sm" type="button" onClick={() => setMaking(true)}>New order</button>}</div>
        {loading && !data ? <p role="status">Loading...</p> : (
          <table>
            <thead><tr><th>#</th><th>Supplier</th><th>Deliver to</th><th>Status</th><th className="n">Ordered</th><th className="n">Received</th><th className="n">Paid</th><th className="n">Owed</th><th></th></tr></thead>
            <tbody>
              {(data || []).map((p) => {
                const [tone, label] = TONE[p.status] || ["y", p.status];
                return (
                  <tr key={p.id}>
                    <td>SPO-{p.id}</td><td>{p.supplier}</td><td>{p.branch}</td><td><span className={`tag ${tone}`}>{label}</span></td>
                    <td className="n">{peso(p.ordered)}</td><td className="n">{peso(p.received)}</td><td className="n">{peso(p.paid)}</td><td className="n">{peso(p.balance)}</td>
                    <td className="rowbtns"><button className="btn ghost sm" type="button" onClick={() => look(p.id)}>Open</button></td>
                  </tr>
                );
              })}
              {data && data.length === 0 && <tr><td colSpan="9" className="emptyrow">No orders yet.</td></tr>}
            </tbody>
          </table>
        )}
      </div>
      {making && <NewOrder suppliers={suppliers.filter((s) => s.active)} items={items.filter((i) => i.active)} branches={branches}
        onClose={() => setMaking(false)} onDone={(id) => { setMaking(false); refresh(id); }} />}
      {open && <OrderDetail po={open} can={can} onClose={() => setOpen(null)} onChanged={() => refresh(open.id)} />}
    </>
  );
}

function NewOrder({ suppliers, items, branches, onClose, onDone }) {
  const [f, setF] = useState({ supplierId: "", branchId: "", note: "", expectedOn: "" });
  const [lines, setLines] = useState([{ itemId: "", qty: "", unitCost: "" }]);
  const [err, setErr] = useState("");
  const setLine = (n, patch) => setLines(lines.map((l, k) => (k === n ? { ...l, ...patch } : l)));

  async function submit(e) {
    e.preventDefault(); setErr("");
    try {
      const r = await api("/api/admin/supplier-pos", { method: "POST", body: {
        supplierId: Number(f.supplierId), branchId: Number(f.branchId), note: f.note, expectedOn: f.expectedOn || null,
        lines: lines.filter((l) => l.itemId).map((l) => ({ itemId: Number(l.itemId), qty: l.qty, unitCost: l.unitCost })),
      } });
      onDone(r.id);
    } catch (e2) { setErr(e2.message); }
  }

  return (
    <Modal title="New purchase order" onClose={onClose}>
      <form onSubmit={submit} className="stack" noValidate>
        <div className="addr"><label htmlFor="nps">Supplier</label>
          <select className="line" id="nps" value={f.supplierId} onChange={(e) => setF({ ...f, supplierId: e.target.value })} required>
            <option value="">Pick a supplier</option>{suppliers.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}</select></div>
        <div className="addr"><label htmlFor="npb">Goods arrive at</label>
          <select className="line" id="npb" value={f.branchId} onChange={(e) => setF({ ...f, branchId: e.target.value })} required>
            <option value="">Pick a branch</option>{branches.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}</select></div>
        {lines.map((l, n) => {
          const it = items.find((i) => String(i.id) === String(l.itemId));
          return (
            <div className="opts" key={n}>
              <select className="line" aria-label="Item" value={l.itemId} onChange={(e) => setLine(n, { itemId: e.target.value })} style={{ flex: 2, minWidth: 150 }}>
                <option value="">Pick an item</option>{items.map((i) => <option key={i.id} value={i.id}>{i.name}</option>)}</select>
              <input aria-label="Quantity" value={l.qty} inputMode="decimal" placeholder={it ? `Qty (${it.unit})` : "Qty"} onChange={(e) => setLine(n, { qty: e.target.value })} style={{ width: 90 }} />
              <input aria-label="Cost per unit" value={l.unitCost} inputMode="decimal" placeholder="Cost each" onChange={(e) => setLine(n, { unitCost: e.target.value })} style={{ width: 90 }} />
              {lines.length > 1 && <button type="button" className="btn ghost sm" onClick={() => setLines(lines.filter((_, k) => k !== n))}>Remove</button>}
            </div>
          );
        })}
        <button type="button" className="btn ghost sm" onClick={() => setLines([...lines, { itemId: "", qty: "", unitCost: "" }])}>Add another item</button>
        <div className="opts">
          <div className="addr" style={{ flex: 1 }}><label htmlFor="npd">Expected on</label><input id="npd" type="date" value={f.expectedOn} onChange={(e) => setF({ ...f, expectedOn: e.target.value })} /></div>
          <div className="addr" style={{ flex: 2 }}><label htmlFor="npn">Note</label><input id="npn" value={f.note} maxLength={200} onChange={(e) => setF({ ...f, note: e.target.value })} /></div>
        </div>
        {err && <p className="err" role="alert">{err}</p>}
        <div className="opts"><button type="button" className="btn ghost" onClick={onClose}>Cancel</button><button className="btn warm" style={{ flex: 1 }} type="submit">Place order</button></div>
      </form>
    </Modal>
  );
}

function OrderDetail({ po, can, onClose, onChanged }) {
  const [got, setGot] = useState({});
  const [pay, setPay] = useState({ amount: "", method: "BANK", reference: "", paidOn: todayManila() });
  const [err, setErr] = useState("");
  const live = po.status === "ORDERED" || po.status === "PARTIAL";

  async function run(fn) { setErr(""); try { await fn(); await onChanged(); } catch (e) { setErr(e.message); } }

  const receive = () => run(async () => {
    const lines = po.lines.filter((l) => got[l.id]).map((l) => ({ lineId: l.id, qty: got[l.id] }));
    await api(`/api/admin/supplier-pos/${po.id}/receive`, { method: "POST", body: { lines } });
    setGot({});
  });
  const act = (what) => run(() => api(`/api/admin/supplier-pos/${po.id}/${what}`, { method: "POST", body: {} }));
  const payNow = (e) => { e.preventDefault(); run(async () => { await api(`/api/admin/supplier-pos/${po.id}/payments`, { method: "POST", body: pay }); setPay({ ...pay, amount: "", reference: "" }); }); };

  return (
    <Modal title={`SPO-${po.id}: ${po.supplier}`} onClose={onClose}>
      <p className="hint">Goods arrive at {po.branch}. Ordered {dateTimeOf(po.createdAt)}.{po.note ? ` ${po.note}` : ""}</p>
      <table>
        <thead><tr><th>Item</th><th className="n">Ordered</th><th className="n">Received</th><th className="n">Cost each</th>{live && can("supplier_po_edit") && <th>Arrived now</th>}</tr></thead>
        <tbody>
          {po.lines.map((l) => (
            <tr key={l.id}>
              <td>{l.item}</td><td className="n">{q(l.qty)} {l.unit}</td><td className="n">{q(l.receivedQty)}</td><td className="n">{peso(l.unitCost)}</td>
              {live && can("supplier_po_edit") && <td>{l.receivedQty < l.qty && <input aria-label={`${l.item} arrived`} inputMode="decimal" value={got[l.id] || ""} placeholder={q(l.qty - l.receivedQty)} onChange={(e) => setGot({ ...got, [l.id]: e.target.value })} style={{ width: 80 }} />}</td>}
            </tr>
          ))}
        </tbody>
      </table>
      <p>Ordered {peso(po.ordered)} · received {peso(po.received)} · paid {peso(po.paid)} · <b>owed {peso(po.balance)}</b></p>
      {err && <p className="err" role="alert">{err}</p>}
      {live && can("supplier_po_edit") && (
        <div className="opts">
          <button className="btn warm sm" type="button" onClick={receive} disabled={!Object.values(got).some(Boolean)}>Record delivery</button>
          {po.status === "PARTIAL" && <button className="btn ghost sm" type="button" onClick={() => act("close")}>Close short</button>}
          {po.status === "ORDERED" && po.paid === 0 && <button className="btn ghost sm" type="button" onClick={() => act("cancel")}>Cancel order</button>}
        </div>
      )}
      <h3>Payments</h3>
      {po.payments.length === 0 && <p className="hint">Nothing paid yet.</p>}
      {po.payments.map((y) => <div className="r" key={y.id}>{y.paidOn} · {peso(y.amount)} · {y.method}{y.reference ? ` (${y.reference})` : ""} <small>{y.recordedBy}</small></div>)}
      {po.status !== "CANCELLED" && can("supplier_po_edit") && (
        <form onSubmit={payNow} className="opts">
          <input aria-label="Amount paid" value={pay.amount} inputMode="decimal" placeholder="Amount" onChange={(e) => setPay({ ...pay, amount: e.target.value })} style={{ width: 100 }} required />
          <select className="line" aria-label="Method" value={pay.method} onChange={(e) => setPay({ ...pay, method: e.target.value })}>{METHODS.map((m) => <option key={m}>{m}</option>)}</select>
          <input aria-label="Reference" value={pay.reference} placeholder="Reference" onChange={(e) => setPay({ ...pay, reference: e.target.value })} style={{ width: 110 }} />
          <input aria-label="Date paid" type="date" value={pay.paidOn} onChange={(e) => setPay({ ...pay, paidOn: e.target.value })} />
          <button className="btn warm sm" type="submit">Record payment</button>
        </form>
      )}
      <div className="opts"><button type="button" className="btn ghost" style={{ flex: 1 }} onClick={onClose}>Close</button></div>
    </Modal>
  );
}
