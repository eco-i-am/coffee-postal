import { useState } from "react";
import { api } from "../../shared/api.js";
import { useLoad } from "../../shared/hooks.js";
import Modal from "../../shared/Modal.jsx";

const loadBranches = () => api("/api/admin/branches");
const EMPTY = { code: "", name: "", address: "", phone: "" };

export default function BranchesPage() {
  const { data: branches, error, loading, reload } = useLoad(loadBranches);
  const [form, setForm] = useState(EMPTY);
  const [fields, setFields] = useState({});
  const [msg, setMsg] = useState("");
  const [busy, setBusy] = useState(false);
  const [adding, setAdding] = useState(false);
  const [search, setSearch] = useState("");

  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });

  async function add(e) {
    e.preventDefault();
    setBusy(true);
    setFields({});
    setMsg("");
    try {
      const b = await api("/api/admin/branches", { method: "POST", body: form });
      setForm(EMPTY);
      setAdding(false);
      setMsg(`${b.name} added.`);
      reload();
    } catch (err) {
      setFields(err.fields || {});
      setMsg(err.message);
    } finally {
      setBusy(false);
    }
  }

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
          <p>Every Coffee Postal branch. Close one and it can no longer sell.</p>
        </div>
        <div className="toolbar">
          <input className="searchbox" type="search" aria-label="Search branches" placeholder="Search branches" value={search} onChange={(e) => setSearch(e.target.value)} />
          <button className="btn warm sm" type="button" onClick={() => { setMsg(""); setFields({}); setAdding(true); }}>+ Add branch</button>
        </div>
      </header>
      {msg && !adding && <p className="notice" role="status">{msg}</p>}

      <div className="sec fill">
        <p className="hint count">{rows.length} of {(branches || []).length} branches</p>
        {error && <p className="err" role="alert">{error}</p>}
        {loading && !branches ? <p role="status">Loading...</p> : (
          <div className="tw">
            <table className="dense">
              <thead><tr><th>Code</th><th>Name</th><th>Status</th><th></th></tr></thead>
              <tbody>
                {rows.map((b) => (
                  <tr key={b.id}>
                    <td>{b.code}</td>
                    <td><b>{b.name}</b></td>
                    <td><span className={`tag ${b.active ? "g" : "r"}`}>{b.active ? "Open" : "Closed"}</span></td>
                    <td className="rowbtns"><button className="btn ghost sm" type="button" onClick={() => toggle(b)}>{b.active ? "Close" : "Reopen"}</button></td>
                  </tr>
                ))}
                {branches && rows.length === 0 && <tr><td colSpan="4" className="emptyrow">{branches.length === 0 ? "No branches yet. Add the first one." : "No branches match."}</td></tr>}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {adding && (
        <Modal title="Add a branch" onClose={() => setAdding(false)} onSubmit={add}>
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
          </div>
          {msg && <p className="err" role="alert">{msg}</p>}
          <div className="opts">
            <button type="button" className="btn ghost" onClick={() => setAdding(false)}>Cancel</button>
            <button className="btn warm" type="submit" disabled={busy} style={{ flex: 1 }}>Add branch</button>
          </div>
        </Modal>
      )}
    </section>
  );
}
