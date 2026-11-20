import { useCallback, useState } from "react";
import { api } from "../../shared/api.js";
import { useLoad } from "../../shared/hooks.js";
import { useAuth } from "../../app/AuthContext.jsx";
import { dateTimeOf } from "../../shared/format.js";
import Modal from "../../shared/Modal.jsx";
import PagedRows from "../../shared/PagedRows.jsx";

const KINDS = { VIDEO: "Video", DOCUMENT: "Document", LINK: "Link" };
const EMPTY = { title: "", description: "", kind: "VIDEO", url: "" };
const MAX_MB = 8;

/** Training. HQ publishes a video (a link), a document (a file) or a link, and sees who finished each. Branch staff open them and mark them done. */
export default function TrainingPage({ side }) {
  return side === "hq" ? <HqView /> : <BranchView />;
}

function HqView() {
  const { can } = useAuth();
  const { data, error, loading, reload } = useLoad(useCallback(() => api("/api/admin/training"), []));
  const [form, setForm] = useState(EMPTY);
  const [file, setFile] = useState(null);
  const [adding, setAdding] = useState(false);
  const [fields, setFields] = useState({});
  const [msg, setMsg] = useState("");
  const [busy, setBusy] = useState(false);
  const [who, setWho] = useState(null);       // { material, list }
  const [removing, setRemoving] = useState(null);

  async function add(e) {
    e.preventDefault();
    setFields({}); setMsg("");
    if (form.kind === "DOCUMENT" && !file) { setFields({ file: "Choose the file." }); return; }
    if (file && file.size > MAX_MB * 1024 * 1024) { setFields({ file: `Keep it under ${MAX_MB} MB, or share a link instead.` }); return; }
    setBusy(true);
    try {
      const made = await api("/api/admin/training", { method: "POST", body: { ...form, url: form.kind === "DOCUMENT" ? null : form.url } });
      if (form.kind === "DOCUMENT") {
        try { await api(`/api/admin/training/${made.id}/file?name=${encodeURIComponent(file.name)}`, { method: "PUT", blob: file }); }
        catch (e2) { await api(`/api/admin/training/${made.id}`, { method: "DELETE" }).catch(() => {}); throw e2; }
      }
      setAdding(false); setForm(EMPTY); setFile(null);
      reload();
    } catch (err) { setFields(err.fields || {}); setMsg(err.message); } finally { setBusy(false); }
  }

  async function showDone(m) {
    setWho({ material: m, list: null });
    try { setWho({ material: m, list: await api(`/api/admin/training/${m.id}/completions`) }); } catch { setWho({ material: m, list: [] }); }
  }

  async function remove() {
    try { await api(`/api/admin/training/${removing.id}`, { method: "DELETE" }); setMsg("Taken down."); } catch (err) { setMsg(err.message); }
    setRemoving(null); reload();
  }

  const rows = data || [];
  return (
    <section className="fitpage">
      <header className="pghead">
        <div><h1>Training</h1><p>Videos, documents and links for branch staff. You can see who has finished each one.</p></div>
        <div className="toolbar">{can("training_admin_add") && <button className="btn warm sm" type="button" onClick={() => { setForm(EMPTY); setFile(null); setFields({}); setMsg(""); setAdding(true); }}>+ Add training</button>}</div>
      </header>
      {msg && !adding && <p className="notice" role="status">{msg}</p>}
      <div className="sec fill">
        {error && <p className="err" role="alert">{error}</p>}
        {loading && !data ? <p role="status">Loading...</p> : (
          <div className="tw">
            <table className="dense">
              <thead><tr><th>Title</th><th>Type</th><th>Added</th><th className="n">Finished by</th><th></th></tr></thead>
              <tbody>
                <PagedRows rows={rows} render={(m) => (
                  <tr key={m.id} className="clickrow" tabIndex={0} onClick={() => showDone(m)} onKeyDown={(e) => { if (e.key === "Enter" && e.target === e.currentTarget) showDone(m); }}>
                    <td><b>{m.title}</b>{m.description && <><br /><small>{m.description}</small></>}</td>
                    <td>{KINDS[m.kind]}</td>
                    <td>{dateTimeOf(m.createdAt)}</td>
                    <td className="n">{m.done}</td>
                    <td className="rowbtns" onClick={(e) => e.stopPropagation()}>
                      <Open m={m} base="/api/admin/training" />
                      {can("training_admin_delete") && <button className="btn ghost sm" type="button" onClick={() => setRemoving(m)}>Take down</button>}
                    </td>
                  </tr>
                )} />
                {data && rows.length === 0 && <tr><td colSpan="5" className="emptyrow">Nothing published yet.</td></tr>}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {adding && (
        <Modal title="Add training" onClose={() => setAdding(false)} onSubmit={add}>
          <div className="addr"><label htmlFor="ttitle">Title</label>
            <input id="ttitle" value={form.title} maxLength={120} onChange={(e) => setForm({ ...form, title: e.target.value })} autoFocus required />
            {fields.title && <p className="err" role="alert">{fields.title}</p>}</div>
          <div className="addr"><label htmlFor="tdesc">What it covers (optional)</label>
            <input id="tdesc" value={form.description} maxLength={1000} onChange={(e) => setForm({ ...form, description: e.target.value })} /></div>
          <div className="grid2">
            <div className="addr"><label htmlFor="tkind">Type</label>
              <select className="line" id="tkind" value={form.kind} onChange={(e) => setForm({ ...form, kind: e.target.value })}>
                {Object.entries(KINDS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
              </select></div>
            {form.kind === "DOCUMENT" ? (
              <div className="addr"><label htmlFor="tfile">File (PDF, Word, PowerPoint, Excel, JPG, PNG; up to {MAX_MB} MB)</label>
                <input id="tfile" type="file" accept=".pdf,.docx,.pptx,.xlsx,.jpg,.jpeg,.png" onChange={(e) => setFile(e.target.files?.[0] || null)} />
                {fields.file && <p className="err" role="alert">{fields.file}</p>}</div>
            ) : (
              <div className="addr"><label htmlFor="turl">{form.kind === "VIDEO" ? "Video link (YouTube, Drive...)" : "Link"}</label>
                <input id="turl" value={form.url} maxLength={500} placeholder="https://" onChange={(e) => setForm({ ...form, url: e.target.value })} />
                {fields.url && <p className="err" role="alert">{fields.url}</p>}</div>
            )}
          </div>
          {msg && <p className="err" role="alert">{msg}</p>}
          <div className="opts">
            <button type="button" className="btn ghost" onClick={() => setAdding(false)}>Cancel</button>
            <button type="submit" className="btn warm" style={{ flex: 1 }} disabled={busy}>{busy ? "Saving..." : "Publish"}</button>
          </div>
        </Modal>
      )}

      {who && (
        <Modal title={who.material.title} onClose={() => setWho(null)}>
          <p className="hint">Finished by {who.material.done} {who.material.done === 1 ? "person" : "people"}.</p>
          {!who.list ? <p role="status">Loading...</p> : who.list.length === 0 ? <p className="hint">No one yet.</p> : (
            <div className="tw" style={{ maxHeight: 240 }}>
              <table className="dense">
                <thead><tr><th>Name</th><th>Branch</th><th>When</th></tr></thead>
                <tbody>{who.list.map((d, i) => <tr key={i}><td>{d.name}</td><td>{d.branch}</td><td>{dateTimeOf(d.at)}</td></tr>)}</tbody>
              </table>
            </div>
          )}
          <div className="opts"><button type="button" className="btn ghost" style={{ flex: 1 }} onClick={() => setWho(null)}>Close</button></div>
        </Modal>
      )}

      {removing && (
        <Modal title="Take down" onClose={() => setRemoving(null)} onSubmit={(e) => { e.preventDefault(); remove(); }}>
          <h2>Take down "{removing.title}"?</h2>
          <p className="hint">Branches will no longer see it.</p>
          <div className="opts">
            <button type="button" className="btn ghost" onClick={() => setRemoving(null)}>Keep it</button>
            <button type="submit" className="btn danger">Take down</button>
          </div>
        </Modal>
      )}
    </section>
  );
}

/** Opens the link in a new tab, or downloads the document. */
function Open({ m, base }) {
  return m.kind === "DOCUMENT"
    ? <a className="btn ghost sm" href={`${base}/${m.id}/file`} download>Download</a>
    : <a className="btn ghost sm" href={m.url} target="_blank" rel="noopener noreferrer">Open</a>;
}

function BranchView() {
  const { data, error, loading, reload } = useLoad(useCallback(() => api("/api/branch/training"), []));
  const [err, setErr] = useState("");
  async function toggle(m) {
    setErr("");
    try { await api(`/api/branch/training/${m.id}/complete`, { method: m.mine ? "DELETE" : "POST" }); reload(); } catch (e) { setErr(e.message); }
  }
  const rows = data || [];
  const done = rows.filter((m) => m.mine).length;
  return (
    <section className="fitpage">
      <header className="pghead"><div><h1>Training</h1><p>{rows.length ? `You have finished ${done} of ${rows.length}.` : "Videos and guides from HQ."}</p></div></header>
      {(error || err) && <p className="err" role="alert">{error || err}</p>}
      <div className="sec fill">
        {loading && !data ? <p role="status">Loading...</p> : (
          <ul className="annlist">
            {rows.map((m) => (
              <li key={m.id} className={`annitem${m.mine ? "" : " unread"}`} style={{ cursor: "default" }}>
                <span className="anntitle"><b>{m.title}</b> <span className="tag">{KINDS[m.kind]}</span> {m.mine && <span className="tag g">Done</span>}</span>
                {m.description && <span className="annpreview">{m.description}</span>}
                <span className="rowbtns" style={{ marginTop: 6 }}>
                  <Open m={m} base="/api/branch/training" />
                  <button type="button" className="btn ghost sm" onClick={() => toggle(m)}>{m.mine ? "Mark not done" : "Mark as done"}</button>
                </span>
              </li>
            ))}
            {data && rows.length === 0 && <li className="emptyrow">Nothing to study yet.</li>}
          </ul>
        )}
      </div>
    </section>
  );
}
