import { useCallback, useState } from "react";
import { api } from "../../shared/api.js";
import { useLoad } from "../../shared/hooks.js";
import { useAuth } from "../../app/AuthContext.jsx";
import { dateTimeOf } from "../../shared/format.js";
import Modal from "../../shared/Modal.jsx";
import PagedRows from "../../shared/PagedRows.jsx";

const EMPTY = { title: "", body: "", allBranches: true, branchIds: [] };

/** HQ writes announcements to all branches or chosen ones; a branch user reads them and each one is marked read once opened. */
export default function AnnouncementsPage({ side }) {
  return side === "hq" ? <HqView /> : <BranchView />;
}

function HqView() {
  const { can } = useAuth();
  const load = useCallback(() => api("/api/admin/announcements"), []);
  const { data, error, loading, reload } = useLoad(load);
  const branches = useLoad(useCallback(() => api("/api/admin/branches"), [])).data || [];
  const [form, setForm] = useState(EMPTY);
  const [adding, setAdding] = useState(false);
  const [removing, setRemoving] = useState(null);
  const [viewing, setViewing] = useState(null);
  const [fields, setFields] = useState({});
  const [msg, setMsg] = useState("");
  const [busy, setBusy] = useState(false);

  const pick = (id) => setForm((f) => ({ ...f, branchIds: f.branchIds.includes(id) ? f.branchIds.filter((x) => x !== id) : [...f.branchIds, id] }));

  async function send(e) {
    e.preventDefault();
    setBusy(true); setFields({}); setMsg("");
    try {
      await api("/api/admin/announcements", { method: "POST", body: form });
      setAdding(false); setForm(EMPTY);
      setMsg("Announcement sent.");
      reload();
    } catch (err) { setFields(err.fields || {}); setMsg(err.message); } finally { setBusy(false); }
  }

  async function takeDown() {
    setMsg("");
    try {
      await api(`/api/admin/announcements/${removing.id}`, { method: "DELETE" });
      setMsg("Announcement taken down. Branches no longer see it.");
    } catch (err) { setMsg(err.message); }
    setRemoving(null);
    reload();
  }

  const rows = data || [];
  return (
    <section className="fitpage">
      <header className="pghead">
        <div><h1>Announcements</h1><p>Write to every branch, or only the ones you choose.</p></div>
        <div className="toolbar">
          {can("announcements_add") && <button className="btn warm sm" type="button" onClick={() => { setForm(EMPTY); setFields({}); setMsg(""); setAdding(true); }}>+ New announcement</button>}
        </div>
      </header>
      {msg && !adding && <p className="notice" role="status">{msg}</p>}
      <div className="sec fill">
        {error && <p className="err" role="alert">{error}</p>}
        {loading && !data ? <p role="status">Loading...</p> : (
          <div className="tw">
            <table className="dense">
              <thead><tr><th>Sent</th><th>Title</th><th>To</th><th>By</th><th className="n">Read by</th><th>Status</th><th></th></tr></thead>
              <tbody>
                <PagedRows rows={rows} render={(a) => (
                  <tr key={a.id} className="clickrow" tabIndex={0} onClick={() => setViewing(a)} onKeyDown={(e) => { if (e.key === "Enter" && e.target === e.currentTarget) setViewing(a); }}>
                    <td>{dateTimeOf(a.createdAt)}</td>
                    <td><b>{a.title}</b></td>
                    <td>{a.allBranches ? "All branches" : `${a.branches.length} ${a.branches.length === 1 ? "branch" : "branches"}`}</td>
                    <td>{a.by}</td>
                    <td className="n">{a.readers}</td>
                    <td><span className={`tag ${a.removed ? "r" : "g"}`}>{a.removed ? "Taken down" : "Live"}</span></td>
                    <td className="rowbtns" onClick={(e) => e.stopPropagation()}>
                      {!a.removed && can("announcements_delete") && <button className="btn ghost sm" type="button" onClick={() => setRemoving(a)}>Take down</button>}
                    </td>
                  </tr>
                )} />
                {data && rows.length === 0 && <tr><td colSpan="7" className="emptyrow">Nothing sent yet.</td></tr>}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {adding && (
        <Modal title="New announcement" wide onClose={() => setAdding(false)} onSubmit={send}>
          <div className="addr"><label htmlFor="antitle">Title</label>
            <input id="antitle" value={form.title} maxLength={120} onChange={(e) => setForm({ ...form, title: e.target.value })} autoFocus required />
            {fields.title && <p className="err" role="alert">{fields.title}</p>}</div>
          <div className="addr"><label htmlFor="anbody">Message</label>
            <textarea id="anbody" className="line" rows={5} maxLength={4000} value={form.body} onChange={(e) => setForm({ ...form, body: e.target.value })} required />
            {fields.body && <p className="err" role="alert">{fields.body}</p>}</div>
          <label className="check"><input type="checkbox" checked={form.allBranches} onChange={(e) => setForm({ ...form, allBranches: e.target.checked })} /> Send to all branches</label>
          {!form.allBranches && (
            <div className="branchpick" role="group" aria-label="Branches">
              {branches.filter((b) => b.active).map((b) => (
                <label key={b.id} className="check"><input type="checkbox" checked={form.branchIds.includes(b.id)} onChange={() => pick(b.id)} /> {b.name}</label>
              ))}
              {fields.branchIds && <p className="err" role="alert">{fields.branchIds}</p>}
            </div>
          )}
          {msg && <p className="err" role="alert">{msg}</p>}
          <div className="opts">
            <button type="button" className="btn ghost" onClick={() => setAdding(false)}>Cancel</button>
            <button type="submit" className="btn warm" style={{ flex: 1 }} disabled={busy}>Send</button>
          </div>
        </Modal>
      )}

      {viewing && (
        <Modal title={viewing.title} onClose={() => setViewing(null)}>
          <p className="hint">{dateTimeOf(viewing.createdAt)} by {viewing.by} to {viewing.allBranches ? "all branches" : viewing.branches.join(", ")}. Read by {viewing.readers}.</p>
          <p style={{ whiteSpace: "pre-wrap" }}>{viewing.body}</p>
          <div className="opts"><button type="button" className="btn ghost" style={{ flex: 1 }} onClick={() => setViewing(null)}>Close</button></div>
        </Modal>
      )}

      {removing && (
        <Modal title="Take down" onClose={() => setRemoving(null)} onSubmit={(e) => { e.preventDefault(); takeDown(); }}>
          <h2>Take down "{removing.title}"?</h2>
          <p className="hint">Branches will no longer see it. It stays in this list.</p>
          <div className="opts">
            <button type="button" className="btn ghost" onClick={() => setRemoving(null)}>Keep it</button>
            <button type="submit" className="btn danger">Take down</button>
          </div>
        </Modal>
      )}
    </section>
  );
}

function BranchView() {
  const load = useCallback(() => api("/api/branch/announcements"), []);
  const { data, error, loading, reload } = useLoad(load);
  const [open, setOpen] = useState(null);

  async function view(a) {
    setOpen(a);
    if (!a.read) {
      try { await api(`/api/branch/announcements/${a.id}/read`, { method: "POST" }); } catch { /* it stays unread and shows again */ }
      window.dispatchEvent(new Event("cp-announcements"));
      reload();
    }
  }

  const rows = data || [];
  return (
    <section className="fitpage">
      <header className="pghead"><div><h1>Announcements</h1><p>News and notices from HQ.</p></div></header>
      <div className="sec fill">
        {error && <p className="err" role="alert">{error}</p>}
        {loading && !data ? <p role="status">Loading...</p> : (
          <ul className="annlist">
            {rows.map((a) => (
              <li key={a.id}>
                <button type="button" className={`annitem${a.read ? "" : " unread"}`} onClick={() => view(a)}>
                  <span className="anntitle">{!a.read && <i className="anndot" aria-label="Unread" />}<b>{a.title}</b></span>
                  <small>{dateTimeOf(a.createdAt)} · {a.by}</small>
                  <span className="annpreview">{a.body.length > 140 ? `${a.body.slice(0, 140)}...` : a.body}</span>
                </button>
              </li>
            ))}
            {data && rows.length === 0 && <li className="emptyrow">No announcements yet.</li>}
          </ul>
        )}
      </div>
      {open && (
        <Modal title={open.title} onClose={() => setOpen(null)}>
          <p className="hint">{dateTimeOf(open.createdAt)} · {open.by}</p>
          <p style={{ whiteSpace: "pre-wrap" }}>{open.body}</p>
          <div className="opts"><button type="button" className="btn ghost" style={{ flex: 1 }} onClick={() => setOpen(null)}>Close</button></div>
        </Modal>
      )}
    </section>
  );
}
