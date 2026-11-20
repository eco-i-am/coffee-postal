import { Fragment, useCallback, useState } from "react";
import { api } from "../../shared/api.js";
import { useLoad } from "../../shared/hooks.js";
import { PRESETS, presetRange } from "../../shared/dates.js";
import { todayManila } from "../../shared/format.js";
import { deviceName, prettyJson, actionName } from "./device.js";
import PagedRows from "../../shared/PagedRows.jsx";

function Pager({ page, total, size, onPage }) {
  const pages = Math.max(1, Math.ceil(total / size));
  return (
    <div className="opts" aria-label="Pages">
      <button className="btn ghost sm" disabled={page <= 0} onClick={() => onPage(page - 1)}>Newer</button>
      <span role="status">Page {page + 1} of {pages} · {total} {total === 1 ? "entry" : "entries"}</span>
      <button className="btn ghost sm" disabled={page + 1 >= pages} onClick={() => onPage(page + 1)}>Older</button>
    </div>
  );
}

function Filters({ range, setRange, user, setUser, children }) {
  const [preset, setPreset] = useState("7d");
  const [text, setText] = useState(user);
  return (
    <div className="filters">
      <div className="tabs" role="group" aria-label="Date range">
        {PRESETS.map(([k, label]) => <button key={k} className="chip" aria-pressed={preset === k} onClick={() => { setPreset(k); setRange(presetRange(k, todayManila())); }}>{label}</button>)}
      </div>
      <label className="datepick">From
        <input type="date" value={range.from} max={range.to} onChange={(e) => { if (e.target.value) { setPreset(""); setRange({ ...range, from: e.target.value }); } }} /></label>
      <label className="datepick">To
        <input type="date" value={range.to} min={range.from} max={todayManila()} onChange={(e) => { if (e.target.value) { setPreset(""); setRange({ ...range, to: e.target.value }); } }} /></label>
      <form className="datepick" onSubmit={(e) => { e.preventDefault(); setUser(text.trim()); }}>
        <label>Person<input value={text} onChange={(e) => setText(e.target.value)} placeholder="name or username" /></label>
      </form>
      {children}
    </div>
  );
}

function Audit({ range, setRange }) {
  const [user, setUser] = useState("");
  const [action, setAction] = useState("");
  const [page, setPage] = useState(0);
  const [open, setOpen] = useState(null);
  const actions = useLoad(useCallback(() => api("/api/admin/logs/audit/actions"), []));
  const q = `from=${range.from}&to=${range.to}&page=${page}&user=${encodeURIComponent(user)}&action=${encodeURIComponent(action)}`;
  const r = useLoad(useCallback(() => api(`/api/admin/logs/audit?${q}`), [q]));
  const d = r.data;
  return (
    <>
      <Filters range={range} setRange={(x) => { setPage(0); setRange(x); }} user={user} setUser={(u) => { setPage(0); setUser(u); }}>
        <label className="datepick">What happened
          <select value={action} onChange={(e) => { setPage(0); setAction(e.target.value); }}>
            <option value="">Everything</option>
            {(actions.data || []).map((a) => <option key={a} value={a}>{actionName(a)}</option>)}
          </select></label>
      </Filters>
      {r.error && <p className="notice" role="alert">{r.error}</p>}
      {!d ? <p role="status">Loading...</p> : (
        <div className="sec tw">
          <table>
            <thead><tr><th>When</th><th>Who</th><th>What</th><th>Branch</th><th></th></tr></thead>
            <tbody>
              <PagedRows rows={d.rows} render={(a) => (
                <Fragment key={a.id}>
                  <tr>
                    <td>{a.at}</td><td>{a.user}</td>
                    <td><b>{actionName(a.action)}</b><br /><small>{a.entity}{a.entityId ? ` #${a.entityId}` : ""}</small></td>
                    <td>{a.branch || "HQ"}</td>
                    <td>{(a.before || a.after || a.reason) && <button className="btn ghost sm" aria-expanded={open === a.id} onClick={() => setOpen(open === a.id ? null : a.id)}>{open === a.id ? "Hide" : "Details"}</button>}</td>
                  </tr>
                  {open === a.id && (
                    <tr><td colSpan="5">
                      {a.reason && <p><b>Reason:</b> {a.reason}</p>}
                      {a.before && <><small>Before</small><pre>{prettyJson(a.before)}</pre></>}
                      {a.after && <><small>After</small><pre>{prettyJson(a.after)}</pre></>}
                    </td></tr>
                  )}
                </Fragment>
              )} />
              {d.rows.length === 0 && <tr><td colSpan="5" className="emptyrow">Nothing recorded for these filters.</td></tr>}
            </tbody>
          </table>
          <Pager page={d.page} total={d.total} size={d.pageSize} onPage={setPage} />
        </div>
      )}
    </>
  );
}

function Logins({ range, setRange }) {
  const [user, setUser] = useState("");
  const [result, setResult] = useState("");
  const [page, setPage] = useState(0);
  const q = `from=${range.from}&to=${range.to}&page=${page}&user=${encodeURIComponent(user)}&result=${result}`;
  const r = useLoad(useCallback(() => api(`/api/admin/logs/logins?${q}`), [q]));
  const d = r.data;
  return (
    <>
      <Filters range={range} setRange={(x) => { setPage(0); setRange(x); }} user={user} setUser={(u) => { setPage(0); setUser(u); }}>
        <label className="datepick">Result
          <select value={result} onChange={(e) => { setPage(0); setResult(e.target.value); }}>
            <option value="">All</option><option value="success">Signed in</option><option value="failed">Failed</option>
          </select></label>
      </Filters>
      {r.error && <p className="notice" role="alert">{r.error}</p>}
      {!d ? <p role="status">Loading...</p> : (
        <div className="sec tw">
          <table>
            <thead><tr><th>When</th><th>Person</th><th>Result</th><th>Device</th><th>Address</th></tr></thead>
            <tbody>
              <PagedRows rows={d.rows} render={(h) => (
                <tr key={h.id}>
                  <td>{h.at}</td>
                  <td><b>{h.fullName || "Unknown account"}</b><br /><small>{h.username}</small></td>
                  <td><span className={`tag ${h.success ? "g" : "r"}`}>{h.success ? "Signed in" : "Failed"}</span></td>
                  <td title={h.device || ""}>{deviceName(h.device)}</td>
                  <td>{h.ip || ""}</td>
                </tr>
              )} />
              {d.rows.length === 0 && <tr><td colSpan="5" className="emptyrow">Nothing recorded for these filters.</td></tr>}
            </tbody>
          </table>
          <Pager page={d.page} total={d.total} size={d.pageSize} onPage={setPage} />
        </div>
      )}
    </>
  );
}

export default function LogsPage() {
  const [tab, setTab] = useState("audit");
  const [range, setRange] = useState(() => presetRange("7d", todayManila()));
  return (
    <section>
      <header className="pghead"><div><h1>Logs</h1><p>Who changed what, and who signed in. Entries can't be edited or deleted.</p></div></header>
      <div className="tabs" role="tablist" aria-label="Log">
        <button role="tab" aria-selected={tab === "audit"} className="chip" aria-pressed={tab === "audit"} onClick={() => setTab("audit")}>Changes</button>
        <button role="tab" aria-selected={tab === "logins"} className="chip" aria-pressed={tab === "logins"} onClick={() => setTab("logins")}>Sign-ins</button>
      </div>
      {tab === "audit" ? <Audit range={range} setRange={setRange} /> : <Logins range={range} setRange={setRange} />}
    </section>
  );
}
