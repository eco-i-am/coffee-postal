import { useEffect, useState } from "react";
import { api } from "../../shared/api.js";

const pesoText = (c) => (c == null ? "" : (c / 100).toFixed(2));

/** Which add-ons a product offers, and for each branch: on or off, and an optional branch price. */
export default function OptionsModal({ product, addons, onClose, onSaved }) {
  const [picked, setPicked] = useState(null);  // Set of add-on ids
  const [rows, setRows] = useState(null);      // [{ branchId, code, name, available, price }]
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    Promise.all([api(`/api/admin/products/${product.id}/addons`), api(`/api/admin/products/${product.id}/branches`)])
      .then(([ids, branches]) => {
        setPicked(new Set(ids));
        setRows(branches.map((b) => ({ ...b, price: pesoText(b.price) })));
      })
      .catch((e) => setErr(e.message));
  }, [product.id]);

  const setRow = (idx, patch) => setRows(rows.map((r, i) => (i === idx ? { ...r, ...patch } : r)));
  const toggleAddon = (id) => {
    const next = new Set(picked);
    if (next.has(id)) next.delete(id); else next.add(id);
    setPicked(next);
  };

  async function save(e) {
    e.preventDefault();
    setBusy(true); setErr("");
    try {
      await api(`/api/admin/products/${product.id}/addons`, { method: "PUT", body: { addonIds: [...picked] } });
      await api(`/api/admin/products/${product.id}/branches`, {
        method: "PUT",
        body: { branches: rows.map((r) => ({ branchId: r.branchId, available: r.available, price: r.price })) },
      });
      onSaved(`Add-ons and branch settings for ${product.name} saved.`);
    } catch (e2) { setErr(e2.message); setBusy(false); }
  }

  return (
    <div className="scrim" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <form className="modal wide" role="dialog" aria-modal="true" onSubmit={save}>
        <h2>Add-ons and branches: {product.name}</h2>
        {!rows && !err && <p role="status">Loading...</p>}
        {rows && (
          <div className="stack" style={{ gap: 10 }}>
            <h3>Add-ons offered</h3>
            {addons.filter((a) => a.active || picked.has(a.id)).map((a) => (
              <label className="check" key={a.id}>
                <input type="checkbox" checked={picked.has(a.id)} onChange={() => toggleAddon(a.id)} /> {a.name} (&#8369;{(a.price / 100).toFixed(2)})
              </label>
            ))}
            {addons.length === 0 && <p className="hint">No add-ons yet. Add some on the Products page.</p>}

            <h3>Branches</h3>
            <p className="hint">Leave the price empty to use the HQ price (&#8369;{(product.price / 100).toFixed(2)}).</p>
            {rows.map((r, idx) => (
              <div className="opts" key={r.branchId} style={{ alignItems: "center", flexWrap: "nowrap" }}>
                <label className="check" style={{ flex: 1 }}>
                  <input type="checkbox" checked={r.available} onChange={(e) => setRow(idx, { available: e.target.checked })} /> {r.code} {r.name}
                </label>
                <input className="qtyin" aria-label={`Price at ${r.name}`} inputMode="decimal" placeholder="HQ price"
                  value={r.price} onChange={(e) => setRow(idx, { price: e.target.value })} />
              </div>
            ))}
          </div>
        )}
        {err && <p className="err" role="alert">{err}</p>}
        <div className="opts">
          <button type="button" className="btn ghost" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn warm" style={{ flex: 1 }} disabled={busy || !rows}>Save</button>
        </div>
      </form>
    </div>
  );
}
