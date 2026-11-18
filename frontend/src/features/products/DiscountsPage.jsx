import { useState } from "react";
import { api } from "../../shared/api.js";
import { useLoad } from "../../shared/hooks.js";
import { peso } from "../../shared/money.js";
import { useAuth } from "../../app/AuthContext.jsx";

const loadDiscounts = () => api("/api/admin/discounts");
const EMPTY = { code: "", name: "", kind: "PERCENT", value: "", requiresId: false, approvalOver: "", active: true };

const valueText = (d) => (d.kind === "SENIOR_PWD" ? "20% off, VAT removed" : d.kind === "PERCENT" ? `${d.value / 100}%` : peso(d.value));
const toForm = (d) => ({
  code: d.code, name: d.name, kind: d.kind,
  value: d.kind === "PERCENT" ? String(d.value / 100) : d.kind === "FIXED" ? (d.value / 100).toFixed(2) : "",
  requiresId: d.requiresId, approvalOver: d.approvalOver == null ? "" : (d.approvalOver / 100).toFixed(2), active: d.active,
});

export default function DiscountsPage() {
  const { data, error, loading, reload } = useLoad(loadDiscounts);
  const { can } = useAuth();
  const [form, setForm] = useState(EMPTY);
  const [editing, setEditing] = useState(null); // code being edited
  const [fields, setFields] = useState({});
  const [msg, setMsg] = useState("");
  const [busy, setBusy] = useState(false);

  const set = (k) => (e) => setForm({ ...form, [k]: e.target.type === "checkbox" ? e.target.checked : e.target.value });
  const senior = form.kind === "SENIOR_PWD";

  async function save(e) {
    e.preventDefault();
    setBusy(true); setFields({}); setMsg("");
    try {
      if (editing) await api(`/api/admin/discounts/${editing}`, { method: "PUT", body: form });
      else await api("/api/admin/discounts", { method: "POST", body: form });
      setMsg(`${form.name.trim()} saved.`);
      setForm(EMPTY); setEditing(null);
      reload();
    } catch (err) {
      setFields(err.fields || {}); setMsg(err.message);
    } finally { setBusy(false); }
  }

  async function toggle(d) {
    setMsg("");
    try {
      await api(`/api/admin/discounts/${d.code}`, { method: "PUT", body: { ...toForm(d), active: !d.active } });
      reload();
    } catch (err) { setMsg(err.message); }
  }

  const rows = data || [];
  const canSave = editing ? can("discounts_edit") : can("discounts_add");

  return (
    <section>
      <header className="pghead">
        <div>
          <h1>Discounts</h1>
          <p>What cashiers can apply. A discount over its limit needs a manager PIN at the counter.</p>
        </div>
      </header>
      {msg && <p className="notice" role="status">{msg}</p>}
      <div className="two">
        {canSave && (
          <div className="sec">
            <h2>{editing ? "Edit discount" : "Add a discount"}</h2>
            <form onSubmit={save} className="stack" noValidate>
              <div className="addr">
                <label htmlFor="dcode">Code</label>
                <input id="dcode" value={form.code} onChange={set("code")} disabled={!!editing} placeholder="STAFF20" maxLength={20} required />
                {fields.code && <p className="err" role="alert">{fields.code}</p>}
              </div>
              <div className="addr">
                <label htmlFor="dname">Name on the receipt</label>
                <input id="dname" value={form.name} onChange={set("name")} placeholder="Staff 20%" required />
                {fields.name && <p className="err" role="alert">{fields.name}</p>}
              </div>
              {!senior && (
                <>
                  <div className="addr">
                    <label htmlFor="dkind">Type</label>
                    <select className="line" id="dkind" value={form.kind} onChange={set("kind")} disabled={!!editing}>
                      <option value="PERCENT">Percent off</option>
                      <option value="FIXED">Fixed amount off</option>
                    </select>
                  </div>
                  <div className="addr">
                    <label htmlFor="dvalue">{form.kind === "PERCENT" ? "Percent (%)" : "Amount (₱)"}</label>
                    <input id="dvalue" value={form.value} onChange={set("value")} inputMode="decimal" placeholder={form.kind === "PERCENT" ? "10" : "50"} required />
                    {fields.value && <p className="err" role="alert">{fields.value}</p>}
                  </div>
                </>
              )}
              {senior && <p className="hint">Senior / PWD follows the law: VAT is removed, then 20% off. It always asks for an ID number.</p>}
              <div className="addr">
                <label htmlFor="dover">Manager PIN needed above (&#8369;, empty for never)</label>
                <input id="dover" value={form.approvalOver} onChange={set("approvalOver")} inputMode="decimal" placeholder="100" />
                {fields.approvalOver && <p className="err" role="alert">{fields.approvalOver}</p>}
              </div>
              {!senior && <label className="check"><input type="checkbox" checked={form.requiresId} onChange={set("requiresId")} /> Ask for an ID number</label>}
              <label className="check"><input type="checkbox" checked={form.active} onChange={set("active")} /> On the POS</label>
              <div className="opts">
                <button className="btn warm" type="submit" disabled={busy}>{editing ? "Save changes" : "Add discount"}</button>
                {editing && <button className="btn ghost" type="button" onClick={() => { setEditing(null); setForm(EMPTY); setFields({}); }}>Cancel</button>}
              </div>
            </form>
          </div>
        )}
        <div className="sec tw">
          <h2>All discounts</h2>
          {error && <p className="err" role="alert">{error}</p>}
          {loading && !data ? <p role="status">Loading...</p> : (
            <table>
              <thead><tr><th>Name</th><th>Takes off</th><th>PIN above</th><th>Status</th><th></th></tr></thead>
              <tbody>
                {rows.map((d) => (
                  <tr key={d.code}>
                    <td><b>{d.name}</b><br /><small>{d.code}{d.requiresId ? " · asks for ID" : ""}</small></td>
                    <td>{valueText(d)}</td>
                    <td>{d.approvalOver == null ? "Never" : peso(d.approvalOver)}</td>
                    <td><span className={`tag ${d.active ? "g" : "r"}`}>{d.active ? "On" : "Off"}</span></td>
                    <td className="rowbtns">
                      {can("discounts_edit") && <button className="btn ghost sm" type="button" onClick={() => { setEditing(d.code); setForm(toForm(d)); setFields({}); setMsg(""); }}>Edit</button>}
                      {can("discounts_edit") && <button className="btn ghost sm" type="button" onClick={() => toggle(d)}>{d.active ? "Switch off" : "Switch on"}</button>}
                    </td>
                  </tr>
                ))}
                {rows.length === 0 && <tr><td colSpan="5" className="emptyrow">No discounts yet.</td></tr>}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </section>
  );
}
