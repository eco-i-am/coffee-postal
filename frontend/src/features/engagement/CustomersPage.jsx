import { useCallback, useState } from "react";
import { api } from "../../shared/api.js";
import { useLoad } from "../../shared/hooks.js";
import { useAuth } from "../../app/AuthContext.jsx";
import { peso } from "../../shared/money.js";
import { dateTimeOf } from "../../shared/format.js";
import Modal from "../../shared/Modal.jsx";
import PagedRows from "../../shared/PagedRows.jsx";

const EMPTY = { name: "", phone: "", email: "", notes: "" };

/** This branch's customers: name, contact and order history (the paid orders made out to that name). */
export default function CustomersPage() {
  const { can } = useAuth();
  const [q, setQ] = useState("");
  const load = useCallback(() => api(`/api/branch/customers${q.trim() ? `?q=${encodeURIComponent(q.trim())}` : ""}`), [q]);
  const { data, error, loading, reload } = useLoad(load);
  const [form, setForm] = useState(EMPTY);
  const [editId, setEditId] = useState(null);
  const [adding, setAdding] = useState(false);
  const [viewing, setViewing] = useState(null);
  const [history, setHistory] = useState(null);
  const [removing, setRemoving] = useState(null);
  const [fields, setFields] = useState({});
  const [msg, setMsg] = useState("");
  const [busy, setBusy] = useState(false);
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });

  function openForm(c) {
    setMsg(""); setFields({});
    setEditId(c ? c.id : null);
    setForm(c ? { name: c.name, phone: c.phone || "", email: c.email || "", notes: c.notes || "" } : EMPTY);
    setAdding(true);
  }

  async function save(e) {
    e.preventDefault();
    setBusy(true); setFields({}); setMsg("");
    try {
      if (editId) await api(`/api/branch/customers/${editId}`, { method: "PUT", body: form });
      else await api("/api/branch/customers", { method: "POST", body: form });
      setAdding(false); setForm(EMPTY); setEditId(null);
      reload();
    } catch (err) { setFields(err.fields || {}); setMsg(err.message); } finally { setBusy(false); }
  }

  async function show(c) {
    setViewing(c); setHistory(null);
    try { setHistory(await api(`/api/branch/customers/${c.id}/orders`)); } catch { setHistory([]); }
  }

  async function remove() {
    try { await api(`/api/branch/customers/${removing.id}`, { method: "DELETE" }); setMsg(`${removing.name} was deleted.`); } catch (err) { setMsg(err.message); }
    setRemoving(null); reload();
  }

  const rows = data || [];
  return (
    <section className="fitpage">
      <header className="pghead">
        <div><h1>Customers</h1><p>People who buy here. Use their name in the "for" box at the till and their orders show up on their card.</p></div>
        <div className="toolbar">
          <input className="searchbox" type="search" aria-label="Search customers" placeholder="Search name, phone, email" value={q} onChange={(e) => setQ(e.target.value)} />
          {can("branch_customers_add") && <button className="btn warm sm" type="button" onClick={() => openForm(null)}>+ Add customer</button>}
        </div>
      </header>
      {msg && !adding && <p className="notice" role="status">{msg}</p>}
      <div className="sec fill">
        {error && <p className="err" role="alert">{error}</p>}
        {loading && !data ? <p role="status">Loading...</p> : (
          <div className="tw">
            <table className="dense">
              <thead><tr><th>Name</th><th>Phone</th><th>Email</th><th className="n">Orders</th><th className="n">Spent</th><th>Last order</th><th></th></tr></thead>
              <tbody>
                <PagedRows rows={rows} render={(c) => (
                  <tr key={c.id} className="clickrow" tabIndex={0} onClick={() => show(c)} onKeyDown={(e) => { if (e.key === "Enter" && e.target === e.currentTarget) show(c); }}>
                    <td><b>{c.name}</b></td>
                    <td>{c.phone || ""}</td>
                    <td>{c.email || ""}</td>
                    <td className="n">{c.orders}</td>
                    <td className="n">{peso(c.spent)}</td>
                    <td>{c.lastOrder ? dateTimeOf(c.lastOrder) : ""}</td>
                    <td className="rowbtns" onClick={(e) => e.stopPropagation()}>
                      {can("branch_customers_edit") && <button className="btn ghost sm" type="button" onClick={() => openForm(c)}>Edit</button>}
                      {can("branch_customers_delete") && <button className="btn ghost sm iconbtn" type="button" title="Delete customer" aria-label={`Delete ${c.name}`} onClick={() => setRemoving(c)}>
                        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M3 6h18" /><path d="M8 6V4h8v2" /><path d="M6 6l1 14h10l1-14" /><path d="M10 11v5M14 11v5" /></svg>
                      </button>}
                    </td>
                  </tr>
                )} />
                {data && rows.length === 0 && <tr><td colSpan="7" className="emptyrow">{q ? "No customers match." : "No customers yet. Add the first one."}</td></tr>}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {adding && (
        <Modal title={editId ? "Edit customer" : "Add a customer"} onClose={() => setAdding(false)} onSubmit={save}>
          <div className="grid2">
            <div className="addr"><label htmlFor="cname">Name</label>
              <input id="cname" value={form.name} maxLength={60} onChange={set("name")} autoFocus required />
              {fields.name && <p className="err" role="alert">{fields.name}</p>}</div>
            <div className="addr"><label htmlFor="cphone">Phone</label><input id="cphone" value={form.phone} maxLength={40} onChange={set("phone")} /></div>
            <div className="addr"><label htmlFor="cemail">Email</label><input id="cemail" type="email" value={form.email} maxLength={120} onChange={set("email")} /></div>
            <div className="addr"><label htmlFor="cnotes">Notes</label><input id="cnotes" value={form.notes} maxLength={300} onChange={set("notes")} placeholder="Usual order, allergies..." /></div>
          </div>
          {msg && <p className="err" role="alert">{msg}</p>}
          <div className="opts">
            <button type="button" className="btn ghost" onClick={() => setAdding(false)}>Cancel</button>
            <button type="submit" className="btn warm" style={{ flex: 1 }} disabled={busy}>Save</button>
          </div>
        </Modal>
      )}

      {viewing && (
        <Modal title={viewing.name} wide onClose={() => setViewing(null)}>
          <dl className="facts">
            <div><dt>Phone</dt><dd>{viewing.phone || "Not set"}</dd></div>
            <div><dt>Email</dt><dd>{viewing.email || "Not set"}</dd></div>
            <div><dt>Notes</dt><dd>{viewing.notes || "None"}</dd></div>
          </dl>
          <h3>Order history</h3>
          {!history ? <p role="status">Loading...</p> : history.length === 0 ? <p className="hint">No orders under this name yet.</p> : (
            <div className="tw" style={{ maxHeight: 220 }}>
              <table className="dense">
                <thead><tr><th>Order</th><th>When</th><th>Status</th><th className="n">Total</th></tr></thead>
                <tbody>{history.map((o) => <tr key={o.orderNo}><td>#{o.orderNo}</td><td>{dateTimeOf(o.at)}</td><td>{o.status}</td><td className="n">{peso(o.total)}</td></tr>)}</tbody>
              </table>
            </div>
          )}
          <div className="opts"><button type="button" className="btn ghost" style={{ flex: 1 }} onClick={() => setViewing(null)}>Close</button></div>
        </Modal>
      )}

      {removing && (
        <Modal title="Delete customer" onClose={() => setRemoving(null)} onSubmit={(e) => { e.preventDefault(); remove(); }}>
          <h2>Delete {removing.name}?</h2>
          <p className="hint">This removes them from the list. Their past orders stay.</p>
          <div className="opts">
            <button type="button" className="btn ghost" onClick={() => setRemoving(null)}>Keep them</button>
            <button type="submit" className="btn danger">Delete</button>
          </div>
        </Modal>
      )}
    </section>
  );
}
