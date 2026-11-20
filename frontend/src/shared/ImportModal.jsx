import { useState } from "react";
import { api } from "./api.js";
import Modal from "./Modal.jsx";

const TEMPLATES = {
  products: "Name,Category,Price,Cost,SKU,Barcode,Description\nHazelnut Latte,Coffee,120,45,HZL-01,,Sweet and nutty\n",
  items: "Name,Unit,Cost,Category,Low stock,SKU,Barcode,Description\nEspresso beans (1 kg bag),bag,850,,5,BEAN-1,,\n",
};

/** Add many products or items from an Excel or CSV file. The file is checked first; nothing is saved until the person confirms. */
export default function ImportModal({ kind, onClose, onDone }) {
  const [file, setFile] = useState(null);
  const [result, setResult] = useState(null);
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);
  const noun = kind === "products" ? "products" : "items";

  async function run(dryRun) {
    if (!file) { setErr("Choose a file first."); return; }
    setBusy(true);
    setErr("");
    try {
      const blob = new Blob([await file.arrayBuffer()], { type: "application/octet-stream" });
      const r = await api(`/api/admin/${kind}/import?dryRun=${dryRun}`, { method: "POST", blob });
      setResult(r);
      if (!dryRun) onDone(r);
    } catch (e) {
      setErr(e.message);
      setResult(null);
    } finally {
      setBusy(false);
    }
  }

  function template() {
    const url = URL.createObjectURL(new Blob(["﻿" + TEMPLATES[kind]], { type: "text/csv" }));
    const a = document.createElement("a");
    a.href = url;
    a.download = `${kind}-template.csv`;
    a.click();
    URL.revokeObjectURL(url);
  }

  const done = result && !result.dryRun;
  return (
    <Modal title={`Import ${noun}`} wide onClose={onClose}>
      <p className="hint">Choose an Excel (.xlsx) or CSV file. The first row holds the column names. Existing {noun} are never changed, and a row with a problem is skipped and listed below.</p>
      <p className="hint">Needed columns: {kind === "products" ? "Name, Category, Price" : "Name, Unit, Cost"}. Optional: {kind === "products" ? "Cost, " : "Category, Low stock, "}SKU, Barcode, Description.</p>
      <div className="opts">
        <input type="file" aria-label="File to import" accept=".xlsx,.csv,.txt" onChange={(e) => { setFile(e.target.files[0] || null); setResult(null); setErr(""); }} />
        <button className="btn ghost sm" type="button" onClick={template}>Download a template</button>
      </div>
      {err && <p className="err" role="alert">{err}</p>}
      {result && (
        <>
          <p className="notice" role="status">
            {done ? `${result.added} added` : `${result.added} of ${result.total} rows are ready to add`}
            {result.skipped > 0 && `, ${result.skipped} skipped`}.
          </p>
          <div className="tw" style={{ maxHeight: 260 }}>
            <table className="dense">
              <thead><tr><th className="n">Row</th><th>Name</th><th>Result</th></tr></thead>
              <tbody>
                {result.rows.map((r) => (
                  <tr key={r.row}>
                    <td className="n">{r.row}</td>
                    <td>{r.name}</td>
                    <td><span className={`tag ${r.status === "OK" ? "g" : "r"}`}>{r.status === "OK" ? "OK" : "Skipped"}</span> {r.status === "OK" ? "" : r.message}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
      <div className="opts">
        <button className="btn ghost" type="button" onClick={onClose}>{done ? "Close" : "Cancel"}</button>
        {!done && <button className="btn ghost" type="button" disabled={busy || !file} onClick={() => run(true)}>Check file</button>}
        {!done && (
          <button className="btn warm" type="button" disabled={busy || !result || result.added === 0} onClick={() => run(false)} style={{ flex: 1 }}>
            {result && result.added > 0 ? `Import ${result.added} ${noun}` : "Import"}
          </button>
        )}
      </div>
    </Modal>
  );
}
