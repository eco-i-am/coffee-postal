import { useState } from "react";
import { api } from "../../shared/api.js";
import { useLoad } from "../../shared/hooks.js";

const loadBranches = () => api("/api/admin/branches");
const EMPTY = { code: "", name: "", address: "", phone: "" };

export default function BranchesPage() {
  const { data: branches, error, loading, reload } = useLoad(loadBranches);
  const [form, setForm] = useState(EMPTY);
  const [fields, setFields] = useState({});
  const [msg, setMsg] = useState("");
  const [busy, setBusy] = useState(false);

  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });

  async function add(e) {
    e.preventDefault();
    setBusy(true);
    setFields({});
    setMsg("");
    try {
      const b = await api("/api/admin/branches", { method: "POST", body: form });
      setForm(EMPTY);
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

  return (
    <section>
      <header className="pghead">
        <div>
          <h1>Branches</h1>
          <p>Create and manage every Coffee Postal branch.</p>
        </div>
      </header>
      {msg && <p className="notice" role="status">{msg}</p>}

      <div className="two">
        <div className="sec">
          <h2>Add a branch</h2>
          <form onSubmit={add} className="stack" noValidate>
            <div className="addr">
              <label htmlFor="code">Code</label>
              <input id="code" value={form.code} onChange={set("code")} placeholder="CEB-03" required />
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
            <button className="btn warm" type="submit" disabled={busy}>Add branch</button>
          </form>
        </div>

        <div className="sec tw">
          <h2>All branches</h2>
          {error && <p className="err" role="alert">{error}</p>}
          {loading && !branches ? (
            <p role="status">Loading...</p>
          ) : (
            <table>
              <thead>
                <tr><th>Code</th><th>Name</th><th>Status</th><th></th></tr>
              </thead>
              <tbody>
                {(branches || []).map((b) => (
                  <tr key={b.id}>
                    <td>{b.code}</td>
                    <td><b>{b.name}</b></td>
                    <td><span className={`tag ${b.active ? "g" : "r"}`}>{b.active ? "Open" : "Closed"}</span></td>
                    <td><button className="btn ghost sm" type="button" onClick={() => toggle(b)}>{b.active ? "Close" : "Reopen"}</button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </section>
  );
}
