import { useCallback, useEffect, useState } from "react";
import { api } from "../../shared/api.js";
import { useLoad } from "../../shared/hooks.js";
import { useAuth } from "../../app/AuthContext.jsx";
import { fromManilaInput, hoursText, manilaInput, presetRange } from "../../shared/dates.js";
import { dateOf, timeOf, todayManila } from "../../shared/format.js";
import { ExportButton, Kpi, RangeBar } from "../reports/widgets.jsx";
import SharedModal from "../../shared/Modal.jsx";
import FaceEnroll from "./FaceEnroll.jsx";

// what the face check said about a punch (managers only)
const FACE = {
  MATCH: ["g", "Match"], REVIEW: ["r", "Check photo"], NO_FACE: ["y", "No face seen"],
  NOT_ENROLLED: ["", "No face on file"], NOT_CHECKED: ["", "Not checked"],
};
const faceTag = (s) => (s && FACE[s] ? <span className={`tag ${FACE[s][0]}`}>{FACE[s][1]}</span> : null);

function Modal({ title, onClose, children }) { return <SharedModal title={title} onClose={onClose}>{children}</SharedModal>; }


const STATUS = {
  OK: ["g", "Complete"], STILL_IN: ["y", "Still in"], FORGOT_OUT: ["r", "No time out"], NO_TIME_IN: ["r", "No time in"],
};

/** Employees, and who was in. Branch managers manage their own branch; HQ sees every branch (attendance read only). */
export default function EmployeesPage({ side }) {
  const hq = side === "hq";
  const { can } = useAuth();
  const perm = hq ? "admin_employees" : "branch_employees";
  const [tab, setTab] = useState("staff");
  const [branchId, setBranchId] = useState("");
  const [addOpen, setAddOpen] = useState(false);
  const loadBranches = useCallback(() => (hq ? api("/api/admin/branches") : Promise.resolve([])), [hq]);
  const branches = useLoad(loadBranches).data || [];

  return (
    <section className="fitpage">
      <header className="pghead">
        <div>
          <h1>Employees</h1>
          <p>Who works here, their time clock PIN, and the hours they were in.</p>
        </div>
        <div className="toolbar">
          <div className="tabs" role="group" aria-label="Section">
            <button className="chip" aria-pressed={tab === "staff"} onClick={() => setTab("staff")}>Staff</button>
            <button className="chip" aria-pressed={tab === "attendance"} onClick={() => setTab("attendance")}>Attendance</button>
          </div>
          {hq && (
            <select aria-label="Branch" value={branchId} onChange={(e) => setBranchId(e.target.value)}>
              <option value="">All branches</option>
              {branches.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
            </select>
          )}
          {tab === "staff" && can(`${perm}_add`) && <button className="btn warm sm" type="button" onClick={() => setAddOpen(true)}>+ Add employee</button>}
        </div>
      </header>
      {tab === "staff"
        ? <StaffTab hq={hq} perm={perm} branchId={branchId} branches={branches} can={can} addOpen={addOpen} setAddOpen={setAddOpen} />
        : <AttendanceTab hq={hq} branchId={branchId} canFix={!hq && can("branch_employees_edit")} />}
    </section>
  );
}

function StaffTab({ hq, perm, branchId, branches, can, addOpen, setAddOpen }) {
  const base = hq ? "/api/admin/employees" : "/api/branch/employees";
  const load = useCallback(() => api(hq && branchId ? `${base}?branchId=${branchId}` : base), [base, hq, branchId]);
  const { data, error, loading, reload } = useLoad(load);
  const [msg, setMsg] = useState("");
  const [form, setForm] = useState({ fullName: "", position: "", pin: "", branchId: "" });
  const [fields, setFields] = useState({});
  const [editing, setEditing] = useState(null);   // employee being edited
  const [pinFor, setPinFor] = useState(null);     // employee getting a new PIN
  const [faceFor, setFaceFor] = useState(null);   // employee whose face is being saved
  const [modal, setModal] = useState({ fullName: "", position: "", pin: "", err: "" });

  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });
  const rows = data || [];
  useEffect(() => { if (addOpen) { setMsg(""); setFields({}); } }, [addOpen]);

  async function add(e) {
    e.preventDefault();
    setFields({}); setMsg("");
    try {
      const body = hq ? { ...form, branchId: form.branchId ? Number(form.branchId) : null } : form;
      await api(base, { method: "POST", body });
      setMsg(`${form.fullName.trim()} added.`);
      setAddOpen(false);
      setForm({ fullName: "", position: "", pin: "", branchId: form.branchId });
      reload();
    } catch (err) { setFields(err.fields || {}); setMsg(err.message); }
  }

  async function save(emp, patch) {
    setMsg("");
    try {
      await api(`${base}/${emp.id}`, { method: "PUT", body: { fullName: emp.fullName, position: emp.position, status: emp.status, ...patch } });
      reload();
      return true;
    } catch (err) { setMsg(err.message); return false; }
  }

  async function savePin(e) {
    e.preventDefault();
    try {
      await api(`${base}/${pinFor.id}/pin`, { method: "POST", body: { pin: modal.pin } });
      setMsg(`New PIN set for ${pinFor.fullName}.`);
      setPinFor(null);
    } catch (err) { setModal({ ...modal, err: err.message }); }
  }

  async function saveEdit(e) {
    e.preventDefault();
    if (await save(editing, { fullName: modal.fullName, position: modal.position })) setEditing(null);
  }

  return (
    <>
      {msg && <p className="notice" role="status">{msg}</p>}
      <div className="sec fill">
        {error && <p className="err" role="alert">{error}</p>}
        {loading && !data ? <p role="status">Loading...</p> : (
          <div className="tw">
            <table className="dense">
              <thead><tr><th>Name</th>{hq && <th>Branch</th>}<th>Position</th><th>Clock PIN</th><th>Face</th><th>Status</th><th></th></tr></thead>
              <tbody>
                {rows.map((e) => (
                  <tr key={e.id}>
                    <td><b>{e.fullName}</b></td>{hq && <td>{e.branch || "HQ"}</td>}<td>{e.position}</td>
                    <td>{e.branchId == null ? "n/a" : e.hasPin ? "Set" : <span className="tag r">None</span>}</td>
                    <td>{e.branchId == null ? "n/a" : e.faceEnrolled ? <span className="tag g">On file</span> : <span className="tag">None</span>}</td>
                    <td><span className={`tag ${e.status === "ACTIVE" ? "g" : "r"}`}>{e.status === "ACTIVE" ? "Active" : "Left"}</span></td>
                    <td className="rowbtns">
                      {can(`${perm}_edit`) && <button className="btn ghost sm" type="button" onClick={() => { setEditing(e); setModal({ fullName: e.fullName, position: e.position || "", pin: "", err: "" }); }}>Edit</button>}
                      {can(`${perm}_edit`) && e.branchId != null && e.status === "ACTIVE" && <button className="btn ghost sm" type="button" onClick={() => { setMsg(""); setFaceFor(e); }}>Face</button>}
                      {can(`${perm}_edit`) && e.branchId != null && <button className="btn ghost sm" type="button" onClick={() => { setPinFor(e); setModal({ fullName: "", position: "", pin: "", err: "" }); }}>New PIN</button>}
                      {can(`${perm}_edit`) && <button className="btn ghost sm" type="button" onClick={() => save(e, { status: e.status === "ACTIVE" ? "INACTIVE" : "ACTIVE" })}>{e.status === "ACTIVE" ? "Left" : "Back"}</button>}
                    </td>
                  </tr>
                ))}
                {rows.length === 0 && <tr><td colSpan={hq ? 7 : 6} className="emptyrow">No employees yet.</td></tr>}
              </tbody>
            </table>
          </div>
        )}
      </div>
      {addOpen && can(`${perm}_add`) && (
        <Modal title="Add an employee" onClose={() => setAddOpen(false)}>
          <form onSubmit={add} className="stack" noValidate>
            <div className="addr"><label htmlFor="efn">Full name</label>
              <input id="efn" value={form.fullName} onChange={set("fullName")} required autoFocus />
              {fields.fullName && <p className="err" role="alert">{fields.fullName}</p>}</div>
            <div className="grid2">
              <div className="addr"><label htmlFor="epos">Position</label>
                <input id="epos" value={form.position} onChange={set("position")} placeholder="Barista" /></div>
              {hq && (
                <div className="addr"><label htmlFor="ebr">Branch</label>
                  <select className="line" id="ebr" value={form.branchId} onChange={set("branchId")}>
                    <option value="">HQ staff (no time clock)</option>
                    {branches.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
                  </select></div>
              )}
              {(!hq || form.branchId) && (
                <div className="addr"><label htmlFor="epin">Time clock PIN (6 digits)</label>
                  <input id="epin" value={form.pin} onChange={set("pin")} inputMode="numeric" maxLength={6} autoComplete="off" placeholder="Not used by anyone else here" />
                  {fields.pin && <p className="err" role="alert">{fields.pin}</p>}</div>
              )}
            </div>
            {msg && <p className="err" role="alert">{msg}</p>}
            <div className="opts"><button type="button" className="btn ghost" onClick={() => setAddOpen(false)}>Cancel</button><button className="btn warm" style={{ flex: 1 }} type="submit">Add employee</button></div>
          </form>
        </Modal>
      )}
      {faceFor && <FaceEnroll employee={faceFor} base={base} onClose={() => setFaceFor(null)} onDone={(m) => { setFaceFor(null); setMsg(m); reload(); }} />}
      {editing && (
        <Modal title={`Edit ${editing.fullName}`} onClose={() => setEditing(null)}>
          <form onSubmit={saveEdit} className="stack">
            <div className="addr"><label htmlFor="mfn">Full name</label><input id="mfn" value={modal.fullName} onChange={(e) => setModal({ ...modal, fullName: e.target.value })} required /></div>
            <div className="addr"><label htmlFor="mpos">Position</label><input id="mpos" value={modal.position} onChange={(e) => setModal({ ...modal, position: e.target.value })} /></div>
            <div className="opts"><button type="button" className="btn ghost" onClick={() => setEditing(null)}>Cancel</button><button className="btn warm" style={{ flex: 1 }} type="submit">Save</button></div>
          </form>
        </Modal>
      )}
      {pinFor && (
        <Modal title={`New PIN for ${pinFor.fullName}`} onClose={() => setPinFor(null)}>
          <form onSubmit={savePin} className="stack">
            <p className="hint">Tell them the new PIN in person. It replaces the old one on every tablet the next time it connects.</p>
            <div className="addr"><label htmlFor="npin">6-digit PIN</label>
              <input id="npin" value={modal.pin} onChange={(e) => setModal({ ...modal, pin: e.target.value })} inputMode="numeric" maxLength={6} autoComplete="off" autoFocus required /></div>
            {modal.err && <p className="err" role="alert">{modal.err}</p>}
            <div className="opts"><button type="button" className="btn ghost" onClick={() => setPinFor(null)}>Cancel</button><button className="btn warm" style={{ flex: 1 }} type="submit">Set PIN</button></div>
          </form>
        </Modal>
      )}
    </>
  );
}

function AttendanceTab({ hq, branchId, canFix }) {
  const [range, setRange] = useState(() => presetRange("7d", todayManila()));
  const load = useCallback(() => {
    const q = new URLSearchParams({ from: range.from, to: range.to });
    if (hq && branchId) q.set("branchId", branchId);
    return api(`${hq ? "/api/admin/attendance" : "/api/branch/attendance"}?${q}`);
  }, [hq, branchId, range.from, range.to]);
  const { data, error, loading, reload } = useLoad(load);
  const [look, setLook] = useState(null);   // { src, label }
  const [fix, setFix] = useState(null);   // { mode: "edit" | "add", logId?, employeeId, employee, kind, at, err }
  const [msg, setMsg] = useState("");
  const [form, setForm] = useState({ when: "", reason: "", pin: "" });
  const [busy, setBusy] = useState(false);
  const sessions = data?.sessions || [];

  const open = (f) => { setFix({ ...f, err: "" }); setForm({ when: f.at ? manilaInput(f.at) : manilaInput(new Date().toISOString()), reason: "", pin: "" }); };

  async function submit(e) {
    e.preventDefault();
    const punchedAt = fromManilaInput(form.when);
    if (!punchedAt) { setFix({ ...fix, err: "Pick the date and time." }); return; }
    setBusy(true);
    try {
      if (fix.mode === "edit") {
        await api(`/api/branch/attendance/${fix.logId}`, { method: "PUT", body: { punchedAt, reason: form.reason, managerPin: form.pin } });
      } else {
        await api("/api/branch/attendance", { method: "POST", body: { employeeId: fix.employeeId, kind: fix.kind, punchedAt, reason: form.reason, managerPin: form.pin } });
      }
      setMsg(`${fix.employee}: time saved.`);
      setFix(null);
      reload();
    } catch (err) { setFix({ ...fix, err: err.message }); } finally { setBusy(false); }
  }

  const exportRows = (() => {
    return [
      ["Date", "Employee", "Branch", "Time in", "Time out", "Hours", "Status", "Photo in", "Photo out", "Notes"],
      ...sessions.map((s) => [s.date, s.employee, s.branch, s.inAt ? timeOf(s.inAt) : "", s.outAt ? timeOf(s.outAt) : "",
        s.minutes == null ? "" : (s.minutes / 60).toFixed(2), STATUS[s.status]?.[1] || s.status, s.inLogId ? (s.inPhoto ? "yes" : "no") : "", s.outLogId ? (s.outPhoto ? "yes" : "no") : "", s.notes.join("; ")]),
    ];
  })();

  const photoUrl = (logId) => `${hq ? "/api/admin/attendance/photos" : "/api/branch/attendance/photos"}/${logId}`;
  const shot = (s, kind) => {
    const logId = kind === "in" ? s.inLogId : s.outLogId;
    const has = kind === "in" ? s.inPhoto : s.outPhoto;
    if (!logId) return null;
    if (has) return <img className="thumb" src={photoUrl(logId)} alt={`${s.employee} time ${kind}`} loading="lazy" onClick={() => setLook({ src: photoUrl(logId), label: `${s.employee}, time ${kind}` })} />;
    return s.notes.includes("Added by a manager") ? null : <small>No photo</small>;
  };

  const toCheck = sessions.filter((s) => s.inFace === "REVIEW" || s.outFace === "REVIEW").length;
  const missing = sessions.filter((s) => s.status === "FORGOT_OUT" || s.status === "NO_TIME_IN").length;

  return (
    <>
      <div className="ctl"><RangeBar range={range} setRange={setRange} /><ExportButton name="attendance" rows={exportRows} range={range} /></div>
      {msg && <p className="notice" role="status">{msg}</p>}
      {error && <p className="notice" role="alert">{error}</p>}
      <div className="kpis slim">
        <Kpi label="Total hours" value={hoursText(data?.totalMinutes ?? 0) || "0m"} note={`${sessions.length} shifts`} />
        <Kpi label="Photos to check" value={toCheck} bad={toCheck > 0} note={toCheck > 0 ? "face did not match" : "none flagged"} />
        <Kpi label="Missing a punch" value={missing} bad={missing > 0} note={missing > 0 ? "needs a manager's fix" : "all complete"} />
      </div>
      <div className="sec fill">
        {loading && !data ? <p role="status">Loading...</p> : (
          <div className="tw">
          <table className="dense">
            <thead><tr><th>Date</th><th>Employee</th>{hq && <th>Branch</th>}<th>Time in</th><th>Time out</th><th className="n">Hours</th><th>Status</th><th>Photos</th><th>Face check</th><th>Notes</th>{canFix && <th></th>}</tr></thead>
            <tbody>
              {sessions.map((s, i) => {
                const [tone, label] = STATUS[s.status] || ["y", s.status];
                return (
                  <tr key={`${s.inLogId}-${s.outLogId}-${i}`}>
                    <td>{dateOf(s.date)}</td><td><b>{s.employee}</b></td>{hq && <td>{s.branch}</td>}
                    <td>{s.inAt ? timeOf(s.inAt) : "-"}</td><td>{s.outAt ? timeOf(s.outAt) : "-"}</td>
                    <td className="n">{hoursText(s.minutes)}</td>
                    <td><span className={`tag ${tone}`}>{label}</span></td>
                    <td><div className="shots">{shot(s, "in")}{shot(s, "out")}</div></td>
                    <td><div className="chips">{faceTag(s.inFace)}{s.outLogId && s.outFace !== s.inFace ? faceTag(s.outFace) : null}</div></td>
                    <td><small>{s.notes.join(", ")}</small></td>
                    {canFix && (
                      <td className="rowbtns">
                        {s.inLogId && <button className="btn ghost sm" type="button" onClick={() => open({ mode: "edit", logId: s.inLogId, employee: s.employee, kind: "IN", at: s.inAt })}>Fix in</button>}
                        {s.outLogId && <button className="btn ghost sm" type="button" onClick={() => open({ mode: "edit", logId: s.outLogId, employee: s.employee, kind: "OUT", at: s.outAt })}>Fix out</button>}
                        {s.inLogId && !s.outLogId && <button className="btn ghost sm" type="button" onClick={() => open({ mode: "add", employeeId: s.employeeId, employee: s.employee, kind: "OUT", at: s.inAt })}>Add out</button>}
                        {!s.inLogId && s.outLogId && <button className="btn ghost sm" type="button" onClick={() => open({ mode: "add", employeeId: s.employeeId, employee: s.employee, kind: "IN", at: s.outAt })}>Add in</button>}
                      </td>
                    )}
                  </tr>
                );
              })}
              {sessions.length === 0 && <tr><td colSpan={hq ? 11 : 10} className="emptyrow">Nobody punched in this range.</td></tr>}
            </tbody>
          </table>
          </div>
        )}
      </div>
      {look && (
        <Modal title={look.label} onClose={() => setLook(null)}>
          <img src={look.src} alt={look.label} style={{ width: "100%", borderRadius: 12 }} />
          <div className="opts"><button type="button" className="btn ghost" style={{ flex: 1 }} onClick={() => setLook(null)}>Close</button></div>
        </Modal>
      )}
      {fix && (
        <Modal title={`${fix.mode === "edit" ? "Fix" : "Add"} ${fix.kind === "IN" ? "time in" : "time out"}: ${fix.employee}`} onClose={() => setFix(null)}>
          <form onSubmit={submit} className="stack">
            <p className="hint">The earlier time stays in the audit log. Say why, and a manager types their PIN.</p>
            <div className="addr"><label htmlFor="fw">Date and time (Manila)</label>
              <input id="fw" type="datetime-local" value={form.when} onChange={(e) => setForm({ ...form, when: e.target.value })} required /></div>
            <div className="addr"><label htmlFor="fr">Reason</label>
              <input id="fr" value={form.reason} onChange={(e) => setForm({ ...form, reason: e.target.value })} maxLength={200} placeholder="Forgot to time out" required /></div>
            <div className="addr"><label htmlFor="fp">Manager PIN</label>
              <input id="fp" type="password" inputMode="numeric" autoComplete="off" maxLength={12} value={form.pin} onChange={(e) => setForm({ ...form, pin: e.target.value })} required /></div>
            {fix.err && <p className="err" role="alert">{fix.err}</p>}
            <div className="opts"><button type="button" className="btn ghost" onClick={() => setFix(null)}>Cancel</button><button className="btn warm" style={{ flex: 1 }} type="submit" disabled={busy}>Save</button></div>
          </form>
        </Modal>
      )}
    </>
  );
}
