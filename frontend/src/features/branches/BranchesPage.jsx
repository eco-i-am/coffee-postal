import { useState } from "react";
import { api } from "../../shared/api.js";
import { useLoad } from "../../shared/hooks.js";
import Modal from "../../shared/Modal.jsx";
import { useAuth } from "../../app/AuthContext.jsx";

const loadBranches = () => api("/api/admin/branches");
const EMPTY = { code: "", name: "", address: "", phone: "", tin: "", vatRegistered: false };

export default function BranchesPage() {
  const { data: branches, error, loading, reload } = useLoad(loadBranches);
  const [form, setForm] = useState(EMPTY);
  const [fields, setFields] = useState({});
  const [msg, setMsg] = useState("");
  const [busy, setBusy] = useState(false);
  const [adding, setAdding] = useState(false);
  const [editId, setEditId] = useState(null);   // null = adding a new branch
  const [search, setSearch] = useState("");
  const { can } = useAuth();
  const [removing, setRemoving] = useState(null);   // the branch being deleted

  const set = (k) => (e) => setForm({ ...form, [k]: e.target.type === "checkbox" ? e.target.checked : e.target.value });

  async function add(e) {
    e.preventDefault();
    setBusy(true);
    setFields({});
    setMsg("");
    try {
      const b = editId
        ? await api(`/api/admin/branches/${editId}`, { method: "PUT", body: form })
        : await api("/api/admin/branches", { method: "POST", body: form });
      setForm(EMPTY);
      setAdding(false);
      setEditId(null);
      setMsg(`${b.name} ${editId ? "saved" : "added"}.`);
      reload();
    } catch (err) {
      setFields(err.fields || {});
      setMsg(err.message);
    } finally {
      setBusy(false);
    }
  }

  function startEdit(b) {
    setMsg(""); setFields({}); setEditId(b.id);
    setForm({ code: b.code, name: b.name, address: b.address || "", phone: b.phone || "", tin: b.tin || "", vatRegistered: !!b.vatRegistered });
    setAdding(true);
  }

  async function remove() {
    setMsg("");
    try {
      await api(`/api/admin/branches/${removing.id}`, { method: "DELETE" });
      setMsg(`${removing.name} was deleted.`);
      setRemoving(null);
      reload();
    } catch (err) {
      setMsg(err.message);
      setRemoving(null);
    }
  }

  function closeForm() { setAdding(false); setEditId(null); setForm(EMPTY); setFields({}); setMsg(""); }

  async function toggle(b) {
    setMsg("");
    try {
      await api(`/api/admin/branches/${b.id}/toggle`, { method: "POST" });
      reload();
    } catch (err) {
      setMsg(err.message);
    }
  }

  const q = search.trim().toLowerCase();
  const rows = (branches || []).filter((b) => !q || `${b.code} ${b.name}`.toLowerCase().includes(q));

  return (
    <section className="fitpage">
      <header className="pghead">
        <div>
          <h1>Branches</h1>
          <p>Every Coffee Postal branch. An inactive branch can no longer sell.</p>
        </div>
        <div className="toolbar">
          <input className="searchbox" type="search" aria-label="Search branches" placeholder="Search branches" value={search} onChange={(e) => setSearch(e.target.value)} />
          <button className="btn warm sm" type="button" onClick={() => { setMsg(""); setFields({}); setEditId(null); setForm(EMPTY); setAdding(true); }}>+ Add branch</button>
        </div>
      </header>
      {msg && !adding && <p className="notice" role="status">{msg}</p>}

      <div className="sec fill">
        <p className="hint count">{rows.length} of {(branches || []).length} branches</p>
        {error && <p className="err" role="alert">{error}</p>}
        {loading && !branches ? <p role="status">Loading...</p> : (
          <div className="tw">
            <table className="dense">
              <thead><tr><th>Code</th><th>Name</th><th>Address</th><th>Phone</th><th>VAT</th><th>Status</th><th></th></tr></thead>
              <tbody>
                {rows.map((b) => (
                  <tr key={b.id}>
                    <td>{b.code}</td>
                    <td><b>{b.name}</b></td>
                    <td>{b.address || ""}</td>
                    <td>{b.phone || ""}</td>
                    <td>{b.vatRegistered ? "Yes" : "No"}</td>
                    <td><span className={`tag ${b.active ? "g" : "r"}`}>{b.active ? "Active" : "Inactive"}</span></td>
                    <td className="rowbtns">
                      <button className="btn ghost sm" type="button" onClick={() => startEdit(b)}>Edit</button>
                      <button className="btn ghost sm" type="button" onClick={() => toggle(b)}>{b.active ? "Set inactive" : "Set active"}</button>
                      {can("branches_delete") && <button className="btn ghost sm" type="button" onClick={() => setRemoving(b)}>Delete</button>}
                    </td>
                  </tr>
                ))}
                {branches && rows.length === 0 && <tr><td colSpan="7" className="emptyrow">{branches.length === 0 ? "No branches yet. Add the first one." : "No branches match."}</td></tr>}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {removing && (
        <Modal title="Delete branch" onClose={() => setRemoving(null)} onSubmit={(e) => { e.preventDefault(); remove(); }}>
          <h2>Delete {removing.name}?</h2>
          <p className="hint">This removes the branch for good and cannot be undone. If it has sales, staff, stock or other records it will not go through; set it to inactive instead.</p>
          <div className="opts">
            <button type="button" className="btn ghost" onClick={() => setRemoving(null)}>Keep it</button>
            <button type="submit" className="btn danger">Delete for good</button>
          </div>
        </Modal>
      )}

      {adding && (
        <Modal title={editId ? "Edit branch" : "Add a branch"} onClose={closeForm} onSubmit={add}>
          <div className="grid2">
            <div className="addr">
              <label htmlFor="code">Code</label>
              <input id="code" value={form.code} onChange={set("code")} placeholder="CEB-03" required autoFocus />
              {fields.code && <p className="err" role="alert">{fields.code}</p>}
            </div>
            <div className="addr">
              <label htmlFor="name">Name</label>
              <input id="name" value={form.name} onChange={set("name")} placeholder="Talisay" required />
              {fields.name && <p className="err" role="alert">{fields.name}</p>}
            </div>
            <div className="addr">
              <label htmlFor="address">Address</label>
              <input id="address" value={form.address} onChange={set("address")} />
            </div>
            <div className="addr">
              <label htmlFor="phone">Phone</label>
              <input id="phone" value={form.phone} onChange={set("phone")} />
            </div>
            {editId && (
              <div className="addr">
                <label htmlFor="tin">TIN</label>
                <input id="tin" value={form.tin} onChange={set("tin")} placeholder="000-000-000-000" />
              </div>
            )}
          </div>
          {editId && (
            <label className="check">
              <input type="checkbox" checked={form.vatRegistered} onChange={set("vatRegistered")} /> VAT registered (receipts show the VAT lines and the TIN)
            </label>
          )}
          {msg && <p className="err" role="alert">{msg}</p>}
          <div className="opts">
            <button type="button" className="btn ghost" onClick={closeForm}>Cancel</button>
            <button className="btn warm" type="submit" disabled={busy} style={{ flex: 1 }}>{editId ? "Save changes" : "Add branch"}</button>
          </div>
        </Modal>
      )}
    </section>
  );
}
