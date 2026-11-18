import { useState } from "react";
import { api } from "../../shared/api.js";
import { useLoad } from "../../shared/hooks.js";
import { peso } from "../../shared/money.js";

const loadItems = () => api("/api/admin/items");
const loadCats = () => api("/api/admin/item-categories");
const loadUnits = () => api("/api/admin/units");
const EMPTY = { name: "", unit: "", cost: "", reorderLevel: "0", categoryId: "" };
const catId = (v) => (v === "" || v == null ? null : Number(v));

export default function ItemsPage() {
  const { data: items, error, loading, reload } = useLoad(loadItems);
  const cats = useLoad(loadCats);
  const units = useLoad(loadUnits).data || [];
  const [newCat, setNewCat] = useState("");
  const [convs, setConvs] = useState([]);   // [{ unit, factor }] for the item being edited
  const [filter, setFilter] = useState("");
  const [form, setForm] = useState(EMPTY);
  const [fields, setFields] = useState({});
  const [msg, setMsg] = useState("");
  const [busy, setBusy] = useState(false);
  const [edit, setEdit] = useState(null); // { id, name, unit, cost, reorderLevel }

  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });

  async function add(e) {
    e.preventDefault();
    setBusy(true); setFields({}); setMsg("");
    try {
      await api("/api/admin/items", { method: "POST", body: { ...form, reorderLevel: Number(form.reorderLevel) || 0, categoryId: catId(form.categoryId) } });
      setMsg(`${form.name.trim()} added. Branches can order it now.`);
      setForm(EMPTY);
      reload();
    } catch (err) {
      setFields(err.fields || {}); setMsg(err.message);
    } finally { setBusy(false); }
  }

  async function save(e) {
    e.preventDefault();
    setMsg("");
    try {
      await api(`/api/admin/items/${edit.id}`, { method: "POST", body: { unit: edit.unit, cost: edit.cost, reorderLevel: Number(edit.reorderLevel) || 0, categoryId: catId(edit.categoryId) } });
      await api(`/api/admin/items/${edit.id}/conversions`, { method: "PUT", body: convs.filter((c) => c.unit.trim() && String(c.factor).trim()) });
      setMsg(`${edit.name} updated.`);
      setEdit(null);
      reload();
    } catch (err) { setMsg(err.message); }
  }

  async function addCat(e) {
    e.preventDefault();
    if (!newCat.trim()) return;
    try { await api("/api/admin/item-categories", { method: "POST", body: { name: newCat } }); setNewCat(""); cats.reload(); }
    catch (err) { setMsg(err.message); }
  }

  async function openEdit(i) {
    setEdit({ ...i, cost: (i.cost / 100).toFixed(2), reorderLevel: String(i.reorderLevel), categoryId: i.categoryId ?? "" });
    try { setConvs(await api(`/api/admin/items/${i.id}/conversions`)); } catch { setConvs([]); }
  }

  async function toggle(i) {
    setMsg("");
    try { await api(`/api/admin/items/${i.id}/toggle`, { method: "POST" }); reload(); }
    catch (err) { setMsg(err.message); }
  }

  return (
    <section>
      <header className="pghead"><div>
        <h1>Items</h1>
        <p>What branches can order from HQ. Prices here are what a branch pays per unit.</p>
      </div></header>
      {msg && <p className="notice" role="status">{msg}</p>}

      <div className="two">
        <div className="sec">
          <h2>Add an item</h2>
          <form onSubmit={add} className="stack" noValidate>
            <div className="addr">
              <label htmlFor="iname">Name</label>
              <input id="iname" value={form.name} onChange={set("name")} placeholder="Espresso beans (1 kg bag)" required />
              {fields.name && <p className="err" role="alert">{fields.name}</p>}
            </div>
            <div className="opts">
              <div className="addr" style={{ flex: 1, minWidth: 110 }}>
                <label htmlFor="iunit">Unit</label>
                <input id="iunit" list="unitlist" value={form.unit} onChange={set("unit")} placeholder="bag" required />
              </div>
              <div className="addr" style={{ flex: 1, minWidth: 110 }}>
                <label htmlFor="icost">Cost per unit (&#8369;)</label>
                <input id="icost" value={form.cost} onChange={set("cost")} inputMode="decimal" placeholder="850.00" required />
                {fields.cost && <p className="err" role="alert">{fields.cost}</p>}
              </div>
            </div>
            <div className="addr">
              <label htmlFor="icat">Category</label>
              <select className="line" id="icat" value={form.categoryId} onChange={set("categoryId")}>
                <option value="">No category</option>
                {(cats.data || []).filter((c) => c.active).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
              </select>
            </div>
            <div className="addr">
              <label htmlFor="ireorder">Warn branches when stock is at or below</label>
              <input id="ireorder" value={form.reorderLevel} onChange={set("reorderLevel")} inputMode="numeric" />
            </div>
            <button className="btn warm" type="submit" disabled={busy}>Add item</button>
          </form>
        </div>

        <div className="sec">
          <h2>Categories</h2>
          <form onSubmit={addCat} className="opts"><input aria-label="New category" value={newCat} onChange={(e) => setNewCat(e.target.value)} placeholder="Packaging" style={{ flex: 1 }} /><button className="btn ghost sm" type="submit">Add</button></form>
          {(cats.data || []).map((c) => <div key={c.id} className="r"><b>{c.name}</b> <small>{c.items} items{c.active ? "" : ", hidden"}</small></div>)}
          {cats.data && cats.data.length === 0 && <p className="hint">No categories yet.</p>}
        </div>

        <div className="sec tw">
          <h2>All items</h2>
          <select className="line" aria-label="Show category" value={filter} onChange={(e) => setFilter(e.target.value)}>
            <option value="">All categories</option>
            <option value="none">No category</option>
            {(cats.data || []).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
          {error && <p className="err" role="alert">{error}</p>}
          {loading && !items ? <p role="status">Loading...</p> : (
            <table>
              <thead><tr><th>Item</th><th>Category</th><th>Unit</th><th className="n">Cost</th><th className="n">Low at</th><th>Status</th><th></th></tr></thead>
              <tbody>
                {(items || []).filter((i) => !filter || (filter === "none" ? i.categoryId == null : String(i.categoryId) === filter)).map((i) => (
                  <tr key={i.id}>
                    <td><b>{i.name}</b></td><td>{i.category || ""}</td><td>{i.unit}</td><td className="n">{peso(i.cost)}</td><td className="n">{i.reorderLevel}</td>
                    <td><span className={`tag ${i.active ? "g" : "r"}`}>{i.active ? "Orderable" : "Hidden"}</span></td>
                    <td className="rowbtns">
                      <button className="btn ghost sm" type="button" onClick={() => openEdit(i)}>Edit</button>
                      <button className="btn ghost sm" type="button" onClick={() => toggle(i)}>{i.active ? "Hide" : "Show"}</button>
                    </td>
                  </tr>
                ))}
                {items && items.length === 0 && <tr><td colSpan="7" className="emptyrow">No items yet. Add the first one.</td></tr>}
              </tbody>
            </table>
          )}
        </div>
      </div>

      {edit && (
        <div className="scrim" onClick={(e) => e.target === e.currentTarget && setEdit(null)}>
          <form className="modal" role="dialog" aria-modal="true" onSubmit={save}>
            <h2>{edit.name}</h2>
            <p className="hint">Changing the cost only affects new orders. Orders already placed keep the price they were made at.</p>
            <div className="addr"><label htmlFor="ecat">Category</label>
              <select className="line" id="ecat" value={edit.categoryId} onChange={(e) => setEdit({ ...edit, categoryId: e.target.value })}>
                <option value="">No category</option>
                {(cats.data || []).filter((c) => c.active || String(c.id) === String(edit.categoryId)).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
              </select></div>
            <div className="addr"><label htmlFor="eunit">Unit</label><input id="eunit" list="unitlist" value={edit.unit} onChange={(e) => setEdit({ ...edit, unit: e.target.value })} required /></div>
            <div className="addr"><label htmlFor="ecost">Cost per unit (&#8369;)</label><input id="ecost" value={edit.cost} inputMode="decimal" onChange={(e) => setEdit({ ...edit, cost: e.target.value })} required /></div>
            <div className="addr"><label htmlFor="ere">Warn when stock is at or below</label><input id="ere" value={edit.reorderLevel} inputMode="numeric" onChange={(e) => setEdit({ ...edit, reorderLevel: e.target.value })} /></div>
            <fieldset className="conv"><legend>Other units (optional)</legend>
              <p className="hint">Say how many {edit.unit || "units"} one of another unit holds, like 1 box = 12. Then orders and transfers can be typed in boxes.</p>
              {convs.map((c, n) => (
                <div className="opts" key={n}>
                  <span>1</span>
                  <input aria-label="Other unit" list="unitlist" value={c.unit} onChange={(e) => setConvs(convs.map((x, k) => (k === n ? { ...x, unit: e.target.value } : x)))} placeholder="box" style={{ width: 90 }} />
                  <span>=</span>
                  <input aria-label={`How many ${edit.unit}`} value={c.factor} inputMode="decimal" onChange={(e) => setConvs(convs.map((x, k) => (k === n ? { ...x, factor: e.target.value } : x)))} placeholder="12" style={{ width: 80 }} />
                  <span>{edit.unit}</span>
                  <button type="button" className="btn ghost sm" onClick={() => setConvs(convs.filter((_, k) => k !== n))}>Remove</button>
                </div>
              ))}
              <button type="button" className="btn ghost sm" onClick={() => setConvs([...convs, { unit: "", factor: "" }])}>Add a unit</button>
            </fieldset>
            <div className="opts">
              <button type="button" className="btn ghost" onClick={() => setEdit(null)}>Cancel</button>
              <button type="submit" className="btn warm" style={{ flex: 1 }}>Save</button>
            </div>
          </form>
        </div>
      )}
    <datalist id="unitlist">{units.map((u) => <option key={u} value={u} />)}</datalist>
    </section>
  );
}
