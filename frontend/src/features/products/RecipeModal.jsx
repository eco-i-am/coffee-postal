import { useEffect, useState } from "react";
import { api } from "../../shared/api.js";
import { fmtQty } from "../../shared/format.js";

/**
 * What ONE sale of this product uses up. Amounts are in the item's own unit:
 * 18 g from a 1 kg bag is 0.018 bag; 200 ml from a 1 L carton is 0.2 carton.
 */
export default function RecipeModal({ product, onClose, onSaved }) {
  const [items, setItems] = useState(null);
  const [rows, setRows] = useState(null); // [{ itemId, qty }]
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    Promise.all([api("/api/admin/items"), api(`/api/admin/products/${product.id}/recipe`)])
      .then(([all, recipe]) => {
        setItems(all.filter((i) => i.active));
        setRows(recipe.map((l) => ({ itemId: String(l.itemId), qty: fmtQty(l.qty) })));
      })
      .catch((e) => setErr(e.message));
  }, [product.id]);

  const unitOf = (id) => items?.find((i) => String(i.id) === String(id))?.unit;
  const set = (idx, patch) => setRows(rows.map((r, i) => (i === idx ? { ...r, ...patch } : r)));
  const used = new Set((rows || []).map((r) => r.itemId));

  async function save(e) {
    e.preventDefault();
    setBusy(true); setErr("");
    try {
      await api(`/api/admin/products/${product.id}/recipe`, {
        method: "POST",
        body: { lines: rows.filter((r) => r.itemId).map((r) => ({ itemId: Number(r.itemId), qty: r.qty })) },
      });
      onSaved(`Recipe for ${product.name} saved.`);
    } catch (e2) { setErr(e2.message); setBusy(false); }
  }

  return (
    <div className="scrim" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <form className="modal wide" role="dialog" aria-modal="true" onSubmit={save}>
        <h2>Recipe: {product.name}</h2>
        <p className="hint">
          What one sale uses up, in each item&apos;s own unit. For example 18 g from a 1 kg bag is <b>0.018</b> bag.
          Stock goes down by this every time the product is sold.
        </p>
        {!rows && !err && <p role="status">Loading...</p>}
        {rows && (
          <div className="stack" style={{ gap: 10 }}>
            {rows.map((r, idx) => (
              <div className="opts" key={idx} style={{ alignItems: "center", flexWrap: "nowrap" }}>
                <select className="line" aria-label="Item" value={r.itemId} onChange={(e) => set(idx, { itemId: e.target.value })} style={{ flex: 1, minWidth: 0 }}>
                  <option value="">Choose an item</option>
                  {items.filter((i) => String(i.id) === r.itemId || !used.has(String(i.id))).map((i) => <option key={i.id} value={i.id}>{i.name}</option>)}
                </select>
                <input className="qtyin" aria-label="Amount used" inputMode="decimal" value={r.qty} onChange={(e) => set(idx, { qty: e.target.value })} />
                <small style={{ width: 52 }}>{unitOf(r.itemId) || ""}</small>
                <button type="button" className="btn ghost sm" aria-label="Remove" onClick={() => setRows(rows.filter((_, i) => i !== idx))}>&times;</button>
              </div>
            ))}
            {rows.length === 0 && <p className="hint">No recipe yet, so selling this does not change stock.</p>}
            <button type="button" className="btn ghost sm" style={{ alignSelf: "start" }} onClick={() => setRows([...rows, { itemId: "", qty: "" }])}>+ Add an item</button>
          </div>
        )}
        {err && <p className="err" role="alert">{err}</p>}
        <div className="opts">
          <button type="button" className="btn ghost" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn warm" style={{ flex: 1 }} disabled={busy || !rows}>Save recipe</button>
        </div>
      </form>
    </div>
  );
}
