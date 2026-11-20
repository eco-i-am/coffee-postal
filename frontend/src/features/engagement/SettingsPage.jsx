import { useCallback, useEffect, useState } from "react";
import { api } from "../../shared/api.js";
import { useLoad } from "../../shared/hooks.js";
import { useAuth } from "../../app/AuthContext.jsx";

/** Settings. HQ: company details, receipt wording and the PO rules. A branch: its own address and phone. */
export default function SettingsPage({ side }) {
  return side === "hq" ? <HqView /> : <BranchView />;
}

function Saved({ at }) { return at ? <p className="notice" role="status">Saved.</p> : null; }

function HqView() {
  const { can } = useAuth();
  const { data, error, loading } = useLoad(useCallback(() => api("/api/admin/settings"), []));
  const [form, setForm] = useState(null);
  const [fields, setFields] = useState({});
  const [msg, setMsg] = useState("");
  const [saved, setSaved] = useState(0);
  const [busy, setBusy] = useState(false);
  useEffect(() => { if (data) setForm(data); }, [data]);
  const edit = can("settings_admin_edit");
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });

  async function save(e) {
    e.preventDefault();
    setBusy(true); setFields({}); setMsg(""); setSaved(0);
    try {
      setForm(await api("/api/admin/settings", { method: "PUT", body: form }));
      try { localStorage.removeItem("cp.receipt.v1"); } catch { /* ignore */ }
      setSaved(Date.now());
    } catch (err) { setFields(err.fields || {}); setMsg(err.message); } finally { setBusy(false); }
  }

  const F = ({ k, label, hint, ...rest }) => (
    <div className="addr"><label htmlFor={`s_${k}`}>{label}</label>
      <input id={`s_${k}`} value={form[k] ?? ""} onChange={set(k)} disabled={!edit} {...rest} />
      {hint && <small className="hint">{hint}</small>}
      {fields[k] && <p className="err" role="alert">{fields[k]}</p>}</div>
  );

  return (
    <section className="fitpage">
      <header className="pghead"><div><h1>Settings</h1><p>Company details, receipt wording and the purchase order rules.</p></div></header>
      {error && <p className="err" role="alert">{error}</p>}
      {loading && !form ? <p role="status">Loading...</p> : form && (
        <form className="sec settingsform" onSubmit={save} noValidate>
          <h2>Company</h2>
          <div className="grid2">
            <F k="company_name" label="Company name" maxLength={120} />
            <F k="company_tin" label="Company TIN" maxLength={40} />
          </div>
          <F k="company_address" label="Company address" maxLength={300} />
          <h2>Receipts</h2>
          <F k="receipt_footer" label="Thank-you line" maxLength={200} hint="Printed at the bottom of every receipt." />
          <F k="receipt_note" label="Small print" maxLength={200} hint="For example: Not an official receipt." />
          <h2>Purchase orders</h2>
          <div className="grid2">
            <F k="po_lead_time_hours" label="Lead time (hours)" inputMode="numeric" maxLength={4} hint="How long ahead a branch must order." />
            <F k="po_late_grace_minutes" label="Late grace (minutes)" inputMode="numeric" maxLength={4} hint="How late an order can arrive before it counts as late." />
          </div>
          {msg && <p className="err" role="alert">{msg}</p>}
          <Saved at={saved} />
          {edit && <div className="opts"><button type="submit" className="btn warm" disabled={busy}>Save settings</button></div>}
        </form>
      )}
    </section>
  );
}

function BranchView() {
  const { can } = useAuth();
  const { data, error, loading } = useLoad(useCallback(() => api("/api/branch/settings"), []));
  const [form, setForm] = useState(null);
  const [msg, setMsg] = useState("");
  const [saved, setSaved] = useState(0);
  const [busy, setBusy] = useState(false);
  useEffect(() => { if (data) setForm({ address: data.address || "", phone: data.phone || "" }); }, [data]);
  const edit = can("branch_settings_edit");

  async function save(e) {
    e.preventDefault();
    setBusy(true); setMsg(""); setSaved(0);
    try { await api("/api/branch/settings", { method: "PUT", body: form }); setSaved(Date.now()); } catch (err) { setMsg(err.message); } finally { setBusy(false); }
  }

  return (
    <section className="fitpage">
      <header className="pghead"><div><h1>Settings</h1><p>Your branch details, as they appear on receipts.</p></div></header>
      {error && <p className="err" role="alert">{error}</p>}
      {loading && !data ? <p role="status">Loading...</p> : data && form && (
        <form className="sec settingsform" onSubmit={save} noValidate>
          <dl className="facts">
            <div><dt>Branch</dt><dd>{data.name}</dd></div>
            <div><dt>Code</dt><dd>{data.code}</dd></div>
            <div><dt>TIN</dt><dd>{data.tin || "Not set (HQ sets this)"}</dd></div>
            <div><dt>VAT registered</dt><dd>{data.vatRegistered ? "Yes" : "No"}</dd></div>
          </dl>
          <div className="addr"><label htmlFor="b_address">Address</label>
            <input id="b_address" value={form.address} maxLength={300} disabled={!edit} onChange={(e) => setForm({ ...form, address: e.target.value })} /></div>
          <div className="addr"><label htmlFor="b_phone">Phone</label>
            <input id="b_phone" value={form.phone} maxLength={40} disabled={!edit} onChange={(e) => setForm({ ...form, phone: e.target.value })} /></div>
          {msg && <p className="err" role="alert">{msg}</p>}
          <Saved at={saved} />
          {edit && <div className="opts"><button type="submit" className="btn warm" disabled={busy}>Save</button></div>}
        </form>
      )}
    </section>
  );
}
