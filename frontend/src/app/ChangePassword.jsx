import { useState } from "react";
import { api } from "../shared/api.js";
import PasswordInput from "../shared/PasswordInput.jsx";

/** Lets anyone signed in change their own password. */
export default function ChangePassword({ onClose }) {
  const [form, setForm] = useState({ current: "", next: "", again: "" });
  const [fields, setFields] = useState({});
  const [err, setErr] = useState("");
  const [done, setDone] = useState(false);
  const [busy, setBusy] = useState(false);
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });

  async function submit(e) {
    e.preventDefault();
    setErr(""); setFields({});
    if (form.next !== form.again) return setFields({ again: "The two new passwords do not match." });
    setBusy(true);
    try {
      await api("/api/me/password", { method: "POST", body: { current: form.current, next: form.next } });
      setDone(true);
    } catch (e2) { setFields(e2.fields || {}); setErr(e2.message); }
    finally { setBusy(false); }
  }

  return (
    <div className="scrim" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <form className="modal" role="dialog" aria-modal="true" aria-label="Change password" onSubmit={submit} noValidate>
        <h2>Change your password</h2>
        {done ? (
          <>
            <p role="status">Done. Your other devices have been signed out.</p>
            <button type="button" className="btn warm" onClick={onClose}>Close</button>
          </>
        ) : (
          <>
            <div className="addr"><label htmlFor="cp0">Current password</label>
              <PasswordInput id="cp0" autoComplete="current-password" value={form.current} onChange={set("current")} />
              {fields.current && <p className="err" role="alert">{fields.current}</p>}</div>
            <div className="addr"><label htmlFor="cp1">New password</label>
              <PasswordInput id="cp1" autoComplete="new-password" value={form.next} onChange={set("next")} />
              <p className="hint">At least 8 characters, with a letter and a number.</p>
              {fields.next && <p className="err" role="alert">{fields.next}</p>}</div>
            <div className="addr"><label htmlFor="cp2">New password again</label>
              <PasswordInput id="cp2" autoComplete="new-password" value={form.again} onChange={set("again")} />
              {fields.again && <p className="err" role="alert">{fields.again}</p>}</div>
            {err && !Object.keys(fields).length && <p className="err" role="alert">{err}</p>}
            <div className="opts">
              <button type="button" className="btn ghost" onClick={onClose}>Cancel</button>
              <button type="submit" className="btn warm" style={{ flex: 1 }} disabled={busy}>Change password</button>
            </div>
          </>
        )}
      </form>
    </div>
  );
}
