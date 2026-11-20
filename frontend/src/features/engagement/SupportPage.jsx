import { useCallback, useEffect, useRef, useState } from "react";
import { api } from "../../shared/api.js";
import { useLoad } from "../../shared/hooks.js";
import { useAuth } from "../../app/AuthContext.jsx";
import { dateTimeOf } from "../../shared/format.js";
import Modal from "../../shared/Modal.jsx";

const POLL_MS = 10000;

/**
 * Support chat. A branch writes to HQ support; HQ replies; each question is its own conversation and the history stays.
 * Lists on the left, the open conversation on the right. It checks for new messages every few seconds while it is open.
 */
export default function SupportPage({ side }) {
  const hq = side === "hq";
  const { can } = useAuth();
  const base = hq ? "/api/admin/support" : "/api/branch/support";
  const perm = hq ? "support" : "branch_support";
  const [filter, setFilter] = useState(hq ? "OPEN" : "");
  const list = useLoad(useCallback(() => api(`${base}${hq && filter ? `?status=${filter}` : ""}`), [base, hq, filter]));
  const [openId, setOpenId] = useState(null);
  const [detail, setDetail] = useState(null);
  const [text, setText] = useState("");
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);
  const [starting, setStarting] = useState(false);
  const [form, setForm] = useState({ subject: "", body: "" });
  const endRef = useRef(null);

  const refreshBadge = () => window.dispatchEvent(new Event("cp-announcements"));

  const loadThread = useCallback(async (id) => {
    try { setDetail(await api(`${base}/${id}`)); refreshBadge(); } catch (e) { setErr(e.message); }
  }, [base]);

  // open conversation and the list refresh while this page is showing
  useEffect(() => {
    if (openId == null) { setDetail(null); return undefined; }
    loadThread(openId);
    const t = setInterval(() => { loadThread(openId); list.reload(); }, POLL_MS);
    return () => clearInterval(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [openId, loadThread]);
  useEffect(() => {
    const t = setInterval(list.reload, POLL_MS * 3);
    return () => clearInterval(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [list.reload]);
  useEffect(() => { endRef.current?.scrollIntoView?.({ block: "end" }); }, [detail?.messages?.length]);

  async function send(e) {
    e.preventDefault();
    if (!text.trim()) return;
    setBusy(true); setErr("");
    try {
      await api(`${base}/${openId}/messages`, { method: "POST", body: { body: text } });
      setText("");
      await loadThread(openId);
      list.reload();
    } catch (e2) { setErr(e2.message); } finally { setBusy(false); }
  }

  async function start(e) {
    e.preventDefault();
    setBusy(true); setErr("");
    try {
      const r = await api(base, { method: "POST", body: form });
      setStarting(false); setForm({ subject: "", body: "" });
      list.reload();
      setOpenId(r.id);
    } catch (e2) { setErr(e2.message); } finally { setBusy(false); }
  }

  async function setStatus(action) {
    setErr("");
    try { await api(`${base}/${openId}/${action}`, { method: "POST" }); await loadThread(openId); list.reload(); } catch (e2) { setErr(e2.message); }
  }

  const threads = list.data || [];
  const t = detail?.thread;
  const closed = t?.status === "CLOSED";

  return (
    <section className="fitpage">
      <header className="pghead">
        <div><h1>Support chat</h1><p>{hq ? "Questions from the branches." : "Ask HQ for help. Replies show here."}</p></div>
        <div className="toolbar">
          {hq && (
            <div className="tabs" role="group" aria-label="Show">
              {[["OPEN", "Open"], ["CLOSED", "Closed"], ["", "All"]].map(([v, l]) => <button key={l} className="chip" aria-pressed={filter === v} onClick={() => setFilter(v)}>{l}</button>)}
            </div>
          )}
          {!hq && can(`${perm}_add`) && <button className="btn warm sm" type="button" onClick={() => { setErr(""); setStarting(true); }}>+ Ask HQ</button>}
        </div>
      </header>

      <div className="supportgrid">
        <ul className="threadlist" aria-label="Conversations">
          {list.error && <li className="err" role="alert">{list.error}</li>}
          {threads.map((x) => (
            <li key={x.id}>
              <button type="button" className={`threaditem${x.id === openId ? " on" : ""}${x.unread ? " unread" : ""}`} onClick={() => { setErr(""); setOpenId(x.id); }}>
                <span className="anntitle">{x.unread > 0 && <i className="anndot" aria-label="Unread" />}<b>{x.subject}</b></span>
                <small>{hq ? `${x.branch} · ` : ""}{dateTimeOf(x.updatedAt)}{x.status === "CLOSED" ? " · Closed" : ""}</small>
                <span className="annpreview">{x.last}</span>
              </button>
            </li>
          ))}
          {list.data && threads.length === 0 && <li className="emptyrow">{hq ? "No conversations here." : "No questions yet. Tap Ask HQ to start one."}</li>}
        </ul>

        <div className="convo">
          {!t ? <p className="hint">Pick a conversation.</p> : (
            <>
              <div className="convohead">
                <div><h2>{t.subject}</h2><small>{t.branch} · started by {t.by}{closed ? " · Closed" : ""}</small></div>
                {hq && can("support_edit") && (closed
                  ? <button className="btn ghost sm" type="button" onClick={() => setStatus("reopen")}>Reopen</button>
                  : <button className="btn ghost sm" type="button" onClick={() => setStatus("close")}>Close</button>)}
              </div>
              <div className="msgs" aria-live="polite">
                {detail.messages.map((m) => (
                  <div key={m.id} className={`msg ${m.fromHq ? "hqmsg" : "brmsg"}${m.fromHq === hq ? " mine" : ""}`}>
                    <small>{m.fromHq ? "HQ support" : m.sender} · {dateTimeOf(m.createdAt)}</small>
                    <p>{m.body}</p>
                  </div>
                ))}
                <div ref={endRef} />
              </div>
              {err && <p className="err" role="alert">{err}</p>}
              {can(`${perm}_add`) && (
                <form className="msgform" onSubmit={send}>
                  <textarea aria-label="Your message" rows={2} maxLength={2000} value={text} onChange={(e) => setText(e.target.value)}
                    onKeyDown={(e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); send(e); } }}
                    placeholder={closed && hq ? "Closed. Reply to open it again" : "Write a message. Enter sends, Shift+Enter is a new line."} />
                  <button className="btn warm" type="submit" disabled={busy || !text.trim()}>Send</button>
                </form>
              )}
            </>
          )}
        </div>
      </div>

      {starting && (
        <Modal title="Ask HQ" onClose={() => setStarting(false)} onSubmit={start}>
          <div className="addr"><label htmlFor="sbj">Subject</label>
            <input id="sbj" value={form.subject} maxLength={120} onChange={(e) => setForm({ ...form, subject: e.target.value })} autoFocus required /></div>
          <div className="addr"><label htmlFor="sbd">Message</label>
            <textarea id="sbd" className="line" rows={4} maxLength={2000} value={form.body} onChange={(e) => setForm({ ...form, body: e.target.value })} required /></div>
          {err && <p className="err" role="alert">{err}</p>}
          <div className="opts">
            <button type="button" className="btn ghost" onClick={() => setStarting(false)}>Cancel</button>
            <button type="submit" className="btn warm" style={{ flex: 1 }} disabled={busy}>Send</button>
          </div>
        </Modal>
      )}
    </section>
  );
}
