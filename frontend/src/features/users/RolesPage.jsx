import { useCallback, useState } from "react";
import { api } from "../../shared/api.js";
import { useLoad } from "../../shared/hooks.js";
import { useAuth } from "../../app/AuthContext.jsx";
import { ACTIONS, gridFrom, gridToPermissions, setAll, toggle } from "./grid.js";

const BLANK = { id: null, name: "", description: "", scope: "BRANCH", locked: false };

export default function RolesPage() {
  const { can } = useAuth();
  const roles = useLoad(useCallback(() => api("/api/admin/roles"), []));
  const modules = useLoad(useCallback(() => api("/api/admin/roles/modules"), []));
  const [form, setForm] = useState(null); // role being edited, with .grid
  const [fields, setFields] = useState({});
  const [err, setErr] = useState("");
  const [msg, setMsg] = useState("");
  const [busy, setBusy] = useState(false);

  async function openRole(r) {
    setFields({}); setErr("");
    if (!r) return setForm({ ...BLANK, grid: {} });
    try {
      const d = await api(`/api/admin/roles/${r.id}`);
      setForm({ ...d.role, grid: gridFrom(d.permissions) });
    } catch (e) { setMsg(e.message); }
  }

  async function save(e) {
    e.preventDefault();
    setBusy(true); setFields({}); setErr("");
    const body = { name: form.name, scope: form.scope, description: form.description, permissions: gridToPermissions(form.grid) };
    try {
      if (form.id) await api(`/api/admin/roles/${form.id}`, { method: "PUT", body });
      else await api("/api/admin/roles", { method: "POST", body });
      setMsg(form.id ? `${form.name} saved. Everyone is signed out so the change takes effect.` : `${form.name} added.`);
      setForm(null); roles.reload();
    } catch (e2) { setFields(e2.fields || {}); setErr(e2.message); }
    finally { setBusy(false); }
  }

  async function remove(r) {
    setMsg("");
    try { await api(`/api/admin/roles/${r.id}`, { method: "DELETE" }); setMsg(`${r.name} deleted.`); roles.reload(); }
    catch (e) { setMsg(e.message); }
  }

  const shown = (modules.data || []).filter((m) => form && (form.scope === "ADMIN" || m.scope === "BRANCH"));
  const ro = form?.locked;

  return (
    <section>
      <header className="pghead">
        <div><h1>Roles</h1><p>What each kind of staff can see and do. You can only give access you have yourself.</p></div>
        {can("roles_add") && <button className="btn warm" onClick={() => openRole(null)}>Add role</button>}
      </header>
      {msg && <p className="notice" role="status">{msg}</p>}
      {roles.error && <p className="err" role="alert">{roles.error}</p>}
      <div className="sec tw">
        <table>
          <thead><tr><th>Role</th><th>For</th><th className="n">Screens</th><th className="n">Staff</th><th></th></tr></thead>
          <tbody>
            {(roles.data || []).map((r) => (
              <tr key={r.id}>
                <td><b>{r.name}</b>{r.description && <><br /><small>{r.description}</small></>}</td>
                <td>{r.scope === "ADMIN" ? "HQ" : "Branch"}</td>
                <td className="n">{r.modules}</td>
                <td className="n">{r.users}</td>
                <td className="rowbtns">
                  <button className="btn ghost sm" onClick={() => openRole(r)}>{r.locked || !can("roles_edit") ? "View" : "Edit"}</button>
                  {can("roles_delete") && !r.locked && <button className="btn ghost sm" disabled={r.users > 0} title={r.users > 0 ? "Move its staff to another role first" : ""} onClick={() => remove(r)}>Delete</button>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {form && (
        <div className="scrim" onClick={(e) => e.target === e.currentTarget && setForm(null)}>
          <form className="modal wide" role="dialog" aria-modal="true" onSubmit={save} noValidate>
            <h2>{form.id ? form.name : "Add role"}</h2>
            {ro && <p className="hint">Super Admin always has full access and cannot be changed.</p>}
            <div className="addr"><label htmlFor="rn">Name</label>
              <input id="rn" value={form.name} disabled={ro} onChange={(e) => setForm({ ...form, name: e.target.value })} />
              {fields.name && <p className="err" role="alert">{fields.name}</p>}</div>
            <div className="addr"><label htmlFor="rd">Description</label>
              <input id="rd" value={form.description || ""} disabled={ro} onChange={(e) => setForm({ ...form, description: e.target.value })} /></div>
            {!form.id && (
              <div className="addr"><label htmlFor="rs">For</label>
                <select id="rs" value={form.scope} onChange={(e) => setForm({ ...form, scope: e.target.value, grid: {} })}>
                  <option value="BRANCH">Branch staff</option><option value="ADMIN">HQ staff</option>
                </select></div>
            )}
            <div className="tw">
              <table>
                <thead><tr><th>Screen</th>{ACTIONS.map((a) => <th key={a} className="n">{a[0].toUpperCase() + a.slice(1)}</th>)}<th></th></tr></thead>
                <tbody>
                  {shown.map((m) => {
                    const row = form.grid[m.code] || {};
                    const mine = ACTIONS.every((a) => can(`${m.code}_${a}`));
                    return (
                      <tr key={m.code}>
                        <td>{m.label}{form.scope === "ADMIN" && <small> ({m.scope === "ADMIN" ? "HQ" : "Branch"})</small>}</td>
                        {ACTIONS.map((a) => (
                          <td key={a} className="n">
                            <input type="checkbox" aria-label={`${m.label} ${a}`} checked={!!row[a]} disabled={ro || !can(`${m.code}_${a}`)}
                              onChange={(e) => setForm({ ...form, grid: toggle(form.grid, m.code, a, e.target.checked) })} />
                          </td>
                        ))}
                        <td>{!ro && mine && <button type="button" className="btn ghost sm" onClick={() => setForm({ ...form, grid: setAll(form.grid, m.code, !ACTIONS.every((a) => row[a])) })}>All</button>}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            {err && !Object.keys(fields).length && <p className="err" role="alert">{err}</p>}
            <div className="opts">
              <button type="button" className="btn ghost" onClick={() => setForm(null)}>{ro ? "Close" : "Cancel"}</button>
              {!ro && (form.id ? can("roles_edit") : can("roles_add")) && <button type="submit" className="btn warm" style={{ flex: 1 }} disabled={busy}>Save</button>}
            </div>
          </form>
        </div>
      )}
    </section>
  );
}
