import { useCallback, useState } from "react";
import { api } from "../../shared/api.js";
import { useLoad } from "../../shared/hooks.js";
import { useAuth } from "../../app/AuthContext.jsx";

const STATUS = { ACTIVE: "Active", DISABLED: "Disabled", BLOCKED: "Blocked" };
const BLANK = { id: null, username: "", fullName: "", email: "", roleId: "", branchId: "", status: "ACTIVE", password: "", pin: "" };

function Field({ id, label, error, children }) {
  return (
    <div className="addr">
      <label htmlFor={id}>{label}</label>
      {children}
      {error && <p className="err" role="alert">{error}</p>}
    </div>
  );
}

function Dialog({ onClose, children, onSubmit }) {
  return (
    <div className="scrim" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <form className="modal" role="dialog" aria-modal="true" onSubmit={onSubmit} noValidate>{children}</form>
    </div>
  );
}

/** side="hq": everyone. side="branch": this branch's staff only. */
export default function StaffPage({ side }) {
  const hq = side === "hq";
  const { can, me } = useAuth();
  const mod = hq ? "users_admin" : "branch_users";
  const base = hq ? "/api/admin/users" : "/api/branch/users";

  const users = useLoad(useCallback(() => api(base), [base]));
  const roles = useLoad(useCallback(() => api(hq ? "/api/admin/roles" : "/api/branch/users/roles"), [hq]));
  const branches = useLoad(useCallback(() => (hq ? api("/api/admin/branches") : Promise.resolve([])), [hq]));

  const [dlg, setDlg] = useState(null); // { kind: "edit"|"password"|"pin", form }
  const [fields, setFields] = useState({});
  const [err, setErr] = useState("");
  const [msg, setMsg] = useState("");
  const [busy, setBusy] = useState(false);

  const roleList = roles.data || [];
  const scopeOf = (roleId) => roleList.find((r) => String(r.id) === String(roleId))?.scope;
  const open = (kind, form) => { setDlg({ kind, form }); setFields({}); setErr(""); };
  const close = () => setDlg(null);
  const setF = (k) => (e) => setDlg({ ...dlg, form: { ...dlg.form, [k]: e.target.value } });

  async function run(fn, done) {
    setBusy(true); setFields({}); setErr("");
    try { await fn(); close(); setMsg(done); users.reload(); roles.reload(); }
    catch (e) { setFields(e.fields || {}); setErr(e.message); }
    finally { setBusy(false); }
  }

  function submit(e) {
    e.preventDefault();
    const f = dlg.form;
    if (dlg.kind === "password") return run(() => api(`${base}/${f.id}/password`, { method: "POST", body: { password: f.password } }), `Password reset for ${f.fullName}. They will need to sign in again.`);
    if (dlg.kind === "delete") return run(() => api(`${base}/${f.id}`, { method: "DELETE" }), `${f.fullName} was deleted.`);
    if (dlg.kind === "pin") return run(() => api(`${base}/${f.id}/pin`, { method: "POST", body: { pin: f.pin } }), `PIN set for ${f.fullName}.`);
    const body = { username: f.username, fullName: f.fullName, email: f.email, roleId: f.roleId ? Number(f.roleId) : null,
      branchId: f.branchId ? Number(f.branchId) : null, status: f.status, password: f.password, pin: f.pin };
    if (f.id) return run(() => api(`${base}/${f.id}`, { method: "PUT", body }), `${f.fullName} saved.`);
    return run(() => api(base, { method: "POST", body }), `${f.fullName} added.`);
  }

  // one click: a disabled person can no longer sign in, and comes back the same way. Their history stays.
  async function setStatus(u, status) {
    setMsg("");
    try {
      await api(`${base}/${u.id}`, { method: "PUT", body: { fullName: u.fullName, email: u.email || "", roleId: u.roleId, branchId: u.branchId || null, status } });
      setMsg(status === "ACTIVE" ? `${u.fullName} can sign in again.` : `${u.fullName} is disabled and can no longer sign in.`);
      users.reload();
    } catch (e) { setMsg(e.message); }
  }

  async function unlock(u) {
    setMsg("");
    try { await api(`${base}/${u.id}/unlock`, { method: "POST" }); setMsg(`${u.fullName} can sign in again.`); users.reload(); }
    catch (e) { setMsg(e.message); }
  }

  const list = users.data;
  const f = dlg?.form;
  const needsBranch = hq && f && scopeOf(f.roleId) === "BRANCH";

  return (
    <section>
      <header className="pghead">
        <div><h1>Staff</h1><p>{hq ? "Everyone who can sign in to the system." : "People who work at this branch."}</p></div>
        {can(`${mod}_add`) && <button className="btn warm" onClick={() => open("edit", { ...BLANK })}>Add staff</button>}
      </header>
      {msg && <p className="notice" role="status">{msg}</p>}
      {users.error && <p className="err" role="alert">{users.error}</p>}
      {users.loading && !list ? <p role="status">Loading...</p> : (
        <div className="sec tw">
          <table>
            <thead><tr><th>Name</th><th>Role</th>{hq && <th>Branch</th>}<th>Status</th><th>Last sign-in</th><th></th></tr></thead>
            <tbody>
              {(list || []).map((u) => (
                <tr key={u.id}>
                  <td><b>{u.fullName}</b><br /><small>{u.username}</small></td>
                  <td>{u.role}</td>
                  {hq && <td>{u.branch || "HQ"}</td>}
                  <td>
                    <span className={`tag ${u.status === "ACTIVE" ? "g" : "r"}`}>{STATUS[u.status]}</span>
                    {u.locked && <span className="tag y">Locked</span>}
                  </td>
                  <td>{u.lastLogin || "Never"}</td>
                  <td className="rowbtns">
                    {can(`${mod}_edit`) && <button className="btn ghost sm" onClick={() => open("edit", { ...BLANK, ...u, email: u.email || "", branchId: u.branchId || "", roleId: u.roleId, password: "", pin: "" })}>Edit</button>}
                    {can(`${mod}_edit`) && <button className="btn ghost sm" onClick={() => open("password", { id: u.id, fullName: u.fullName, password: "" })}>Password</button>}
                    {can(`${mod}_edit`) && <button className="btn ghost sm" onClick={() => open("pin", { id: u.id, fullName: u.fullName, pin: "" })}>{u.hasPin ? "Change PIN" : "Set PIN"}</button>}
                    {can(`${mod}_edit`) && u.locked && <button className="btn ghost sm" onClick={() => unlock(u)}>Unlock</button>}
                    {can(`${mod}_edit`) && u.username !== me.username && <button className="btn ghost sm" onClick={() => setStatus(u, u.status === "ACTIVE" ? "DISABLED" : "ACTIVE")}>{u.status === "ACTIVE" ? "Disable" : "Enable"}</button>}
                    {can(`${mod}_delete`) && u.username !== me.username && <button className="btn ghost sm" onClick={() => open("delete", { id: u.id, fullName: u.fullName })}>Delete</button>}
                  </td>
                </tr>
              ))}
              {list && list.length === 0 && <tr><td colSpan="6" className="emptyrow">No staff yet.</td></tr>}
            </tbody>
          </table>
        </div>
      )}

      {dlg && (
        <Dialog onClose={close} onSubmit={submit}>
          {dlg.kind === "password" && (
            <>
              <h2>New password for {f.fullName}</h2>
              <p className="hint">They are signed out everywhere and use this next time. At least 8 characters with a letter and a number.</p>
              <Field id="np" label="New password" error={fields.password}><input id="np" type="text" autoComplete="off" value={f.password} onChange={setF("password")} /></Field>
            </>
          )}
          {dlg.kind === "delete" && (
            <>
              <h2>Delete {f.fullName}?</h2>
              <p className="hint">This removes the account for good and cannot be undone. If they have sales, shifts or other records it will not go through, and you should disable them instead.</p>
            </>
          )}
          {dlg.kind === "pin" && (
            <>
              <h2>PIN for {f.fullName}</h2>
              <p className="hint">4 to 6 digits. Used to approve refunds. Not 1234 or 1111.</p>
              <Field id="npin" label="PIN" error={fields.pin}><input id="npin" inputMode="numeric" autoComplete="off" maxLength={6} value={f.pin} onChange={setF("pin")} /></Field>
            </>
          )}
          {dlg.kind === "edit" && (
            <>
              <h2>{f.id ? `Edit ${f.fullName}` : "Add staff"}</h2>
              <Field id="fn" label="Full name" error={fields.fullName}><input id="fn" value={f.fullName} onChange={setF("fullName")} /></Field>
              {!f.id && <Field id="un" label="Username" error={fields.username}><input id="un" autoComplete="off" value={f.username} onChange={setF("username")} placeholder="ana.cruz" /></Field>}
              <Field id="em" label="Email (optional)" error={fields.email}><input id="em" type="email" value={f.email} onChange={setF("email")} /></Field>
              <Field id="ro" label="Role" error={fields.roleId}>
                <select id="ro" value={f.roleId} onChange={setF("roleId")}>
                  <option value="">Pick a role</option>
                  {roleList.map((r) => <option key={r.id} value={r.id}>{r.name}{hq ? ` (${r.scope === "ADMIN" ? "HQ" : "Branch"})` : ""}</option>)}
                </select>
              </Field>
              {needsBranch && (
                <Field id="br" label="Branch" error={fields.branchId}>
                  <select id="br" value={f.branchId} onChange={setF("branchId")}>
                    <option value="">Pick a branch</option>
                    {(branches.data || []).filter((b) => b.active !== false).map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
                  </select>
                </Field>
              )}
              {f.id ? (
                <Field id="st" label="Status" error={fields.status}>
                  <select id="st" value={f.status} onChange={setF("status")} disabled={f.username === me.username}>
                    {Object.entries(STATUS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
                  </select>
                </Field>
              ) : (
                <>
                  <Field id="pw" label="Starting password" error={fields.password}><input id="pw" type="text" autoComplete="off" value={f.password} onChange={setF("password")} /></Field>
                  <Field id="pn" label="PIN (optional, for refunds)" error={fields.pin}><input id="pn" inputMode="numeric" autoComplete="off" maxLength={6} value={f.pin} onChange={setF("pin")} /></Field>
                </>
              )}
            </>
          )}
          {err && !Object.keys(fields).length && <p className="err" role="alert">{err}</p>}
          <div className="opts">
            <button type="button" className="btn ghost" onClick={close}>Cancel</button>
            <button type="submit" className={`btn ${dlg.kind === "delete" ? "danger" : "warm"}`} style={{ flex: 1 }} disabled={busy}>{dlg.kind === "delete" ? "Delete for good" : "Save"}</button>
          </div>
        </Dialog>
      )}
    </section>
  );
}
