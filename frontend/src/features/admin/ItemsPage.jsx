import { useState } from "react";
import ImageField, { Thumb } from "../../shared/ImageField.jsx";
import { useImages } from "../../shared/images.js";
import { api } from "../../shared/api.js";
import { useLoad } from "../../shared/hooks.js";
import { peso } from "../../shared/money.js";
import { useAuth } from "../../app/AuthContext.jsx";
import ImportModal from "../../shared/ImportModal.jsx";
import HistoryModal from "../../shared/HistoryModal.jsx";
import LabelModal from "../../shared/LabelModal.jsx";

const loadItems = () => api("/api/admin/items");
const loadCats = () => api("/api/admin/item-categories");
const loadUnits = () => api("/api/admin/units");
const EMPTY = { name: "", unit: "", cost: "", reorderLevel: "0", categoryId: "", sku: "", barcode: "", description: "" };
const catId = (v) => (v === "" || v == null ? null : Number(v));

export default function ItemsPage() {
  const { can } = useAuth();
  const { data: items, error, loading, reload } = useLoad(loadItems);
  const cats = useLoad(loadCats);
  const units = useLoad(loadUnits).data || [];
  const [newCat, setNewCat] = useState("");
  const [convs, setConvs] = useState([]);   // [{ unit, factor }] for the item being edited
  const [filter, setFilter] = useState("");
  const [form, setForm] = useState(EMPTY);
  const [fields, setFields] = useState({});
  const [msg, setMsg] = useState("");
  const [search, setSearch] = useState("");
  const [importing, setImporting] = useState(false);
  const [historyFor, setHistoryFor] = useState(null);
  const [labelFor, setLabelFor] = useState(null);
  const [showAdd, setShowAdd] = useState(false);
  const [showCats, setShowCats] = useState(false);
  const [busy, setBusy] = useState(false);
  const [edit, setEdit] = useState(null); // { id, name, unit, cost, reorderLevel }

  const imgs = useImages();
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });

  async function add(e) {
    e.preventDefault();
    setBusy(true); setFields({}); setMsg("");
    try {
      await api("/api/admin/items", { method: "POST", body: { ...form, reorderLevel: Number(form.reorderLevel) || 0, categoryId: catId(form.categoryId) } });
      setMsg(`${form.name.trim()} added. Branches can order it now.`);
      setForm(EMPTY);
      setShowAdd(false);
      reload();
    } catch (err) {
      setFields(err.fields || {}); setMsg(err.message);
    } finally { setBusy(false); }
  }

  async function save(e) {
    e.preventDefault();
    setMsg("");
    try {
      await api(`/api/admin/items/${edit.id}`, { method: "POST", body: { unit: edit.unit, cost: edit.cost, reorderLevel: Number(edit.reorderLevel) || 0, categoryId: catId(edit.categoryId), sku: edit.sku, barcode: edit.barcode, description: edit.description } });
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
    setEdit({ ...i, cost: (i.cost / 100).toFixed(2), reorderLevel: String(i.reorderLevel), categoryId: i.categoryId ?? "", sku: i.sku || "", barcode: i.barcode || "", description: i.description || "" });
    try { setConvs(await api(`/api/admin/items/${i.id}/conversions`)); } catch { setConvs([]); }
  }

  async function duplicate(i) {
    setMsg("");
    try {
      const r = await api(`/api/admin/items/${i.id}/duplicate`, { method: "POST" });
      setMsg(`${r.name} created. Rename it and set its cost.`);
      reload();
    } catch (e) { setMsg(e.message); }
  }

  async function setOrderable(i) {
    setMsg("");
    try { await api(`/api/admin/items/${i.id}/orderable`, { method: "POST", body: { orderable: !i.orderable } }); reload(); }
    catch (err) { setMsg(err.message); }
  }

  async function toggle(i) {
    setMsg("");
    try { await api(`/api/admin/items/${i.id}/toggle`, { method: "POST" }); reload(); }
    catch (err) { setMsg(err.message); }
  }

  const q = search.trim().toLowerCase();
  const shown = (items || []).filter((i) =>
    (!filter || (filter === "none" ? i.categoryId == null : String(i.categoryId) === filter)) &&
    (!q || [i.name, i.sku, i.barcode].some((v) => v && v.toLowerCase().includes(q))));

  return (
    <section className="fitpage">
      <header className="pghead"><div>
        <h1>Items</h1>
        <p>What a branch pays per unit, and which items branches can order from HQ.</p>
      </div>
        <div className="toolbar">
          <input className="searchbox" type="search" aria-label="Search items" placeholder="Search name, SKU or barcode" value={search} onChange={(e) => setSearch(e.target.value)} />
          <select aria-label="Show category" value={filter} onChange={(e) => setFilter(e.target.value)}>
            <option value="">All categories</option>
            <option value="none">No category</option>
            {(cats.data || []).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
          {can("items_master_add") && <button className="btn ghost sm" type="button" onClick={() => setImporting(true)}>Import</button>}
          <button className="btn ghost sm" type="button" onClick={() => setShowCats(true)}>Categories</button>
          <button className="btn warm sm" type="button" onClick={() => { setMsg(""); setFields({}); setShowAdd(true); }}>+ Add item</button>
        </div>
      </header>
      {msg && <p className="notice" role="status">{msg}</p>}

      <div className="sec fill">
        <p className="hint count">{shown.length} of {(items || []).length} items</p>
        {error && <p className="err" role="alert">{error}</p>}
        {loading && !items ? <p role="status">Loading...</p> : (
          <div className="tw">
            <table className="dense">
              <thead><tr><th></th><th>Item</th><th>SKU</th><th>Category</th><th>Unit</th><th className="n">Cost</th><th className="n">Low at</th><th>Orderable</th><th>Status</th><th></th></tr></thead>
              <tbody>
                {shown.map((i) => (
                  <tr key={i.id}>
                    <td className="thc"><Thumb url={imgs.url("item", i.id)} alt="" /></td>
                    <td><b>{i.name}</b></td><td>{i.sku || ""}</td><td>{i.category || ""}</td><td>{i.unit}</td><td className="n">{peso(i.cost)}</td><td className="n">{i.reorderLevel}</td>
                    <td>{can("po_items_edit")
                      ? <button className="btn ghost sm" type="button" aria-pressed={i.orderable} onClick={() => setOrderable(i)}>{i.orderable ? "Yes" : "No"}</button>
                      : (i.orderable ? "Yes" : "No")}</td>
                    <td><span className={`tag ${i.active ? "g" : "r"}`}>{i.active ? "Active" : "Hidden"}</span></td>
                    <td className="rowbtns">
                      <button className="btn ghost sm" type="button" onClick={() => openEdit(i)}>Edit</button>
                      {can("items_master_add") && <button className="btn ghost sm" type="button" onClick={() => duplicate(i)}>Duplicate</button>}
                      <button className="btn ghost sm" type="button" onClick={() => setHistoryFor(i)}>History</button>
                      {(i.sku || i.barcode) && <button className="btn ghost sm" type="button" onClick={() => setLabelFor(i)}>Label</button>}
                      <button className="btn ghost sm" type="button" onClick={() => toggle(i)}>{i.active ? "Hide" : "Show"}</button>
                    </td>
                  </tr>
                ))}
                {items && shown.length === 0 && <tr><td colSpan="10" className="emptyrow">{items.length === 0 ? "No items yet. Add the first one." : "No items match."}</td></tr>}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {showAdd && (
        <div className="scrim" onClick={(e) => e.target === e.currentTarget && setShowAdd(false)}>
          <form onSubmit={add} className="modal" role="dialog" aria-modal="true" aria-label="Add an item" noValidate>
            <h2>Add an item</h2>
            <div className="addr">
              <label htmlFor="iname">Name</label>
              <input id="iname" value={form.name} onChange={set("name")} placeholder="Espresso beans (1 kg bag)" required autoFocus />
              {fields.name && <p className="err" role="alert">{fields.name}</p>}
            </div>
            <div className="grid2">
              <div className="addr">
                <label htmlFor="iunit">Unit</label>
                <input id="iunit" list="unitlist" value={form.unit} onChange={set("unit")} placeholder="bag" required />
              </div>
              <div className="addr">
                <label htmlFor="icost">Cost per unit (&#8369;)</label>
                <input id="icost" value={form.cost} onChange={set("cost")} inputMode="decimal" placeholder="850.00" required />
                {fields.cost && <p className="err" role="alert">{fields.cost}</p>}
              </div>
              <div className="addr">
                <label htmlFor="icat">Category</label>
                <select className="line" id="icat" value={form.categoryId} onChange={set("categoryId")}>
                  <option value="">No category</option>
                  {(cats.data || []).filter((c) => c.active).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
                </select>
              </div>
              <div className="addr">
                <label htmlFor="ireorder">Warn when stock is at or below</label>
                <input id="ireorder" value={form.reorderLevel} onChange={set("reorderLevel")} inputMode="numeric" />
              </div>
            </div>
            <div className="grid2">
              <div className="addr"><label htmlFor="isku">SKU (optional)</label><input id="isku" value={form.sku} onChange={set("sku")} maxLength={40} />{fields.sku && <p className="err" role="alert">{fields.sku}</p>}</div>
              <div className="addr"><label htmlFor="ibar">Barcode (optional)</label><input id="ibar" value={form.barcode} onChange={set("barcode")} maxLength={40} />{fields.barcode && <p className="err" role="alert">{fields.barcode}</p>}</div>
            </div>
            <div className="addr"><label htmlFor="idesc">Description (optional)</label><textarea id="idesc" className="line" rows={2} maxLength={500} value={form.description} onChange={set("description")} /></div>
            {msg && <p className="err" role="alert">{msg}</p>}
            <div className="opts">
              <button type="button" className="btn ghost" onClick={() => setShowAdd(false)}>Cancel</button>
              <button className="btn warm" type="submit" disabled={busy} style={{ flex: 1 }}>Add item</button>
            </div>
          </form>
        </div>
      )}

      {showCats && (
        <div className="scrim" onClick={(e) => e.target === e.currentTarget && setShowCats(false)}>
          <div className="modal" role="dialog" aria-modal="true" aria-label="Categories">
            <h2>Categories</h2>
            <form onSubmit={addCat} className="opts"><input aria-label="New category" value={newCat} onChange={(e) => setNewCat(e.target.value)} placeholder="Packaging" style={{ flex: 1 }} /><button className="btn ghost sm" type="submit">Add</button></form>
            <div className="catlist">
              {(cats.data || []).map((c) => <div key={c.id} className="r"><b>{c.name}</b> <small>{c.items} items{c.active ? "" : ", hidden"}</small></div>)}
              {cats.data && cats.data.length === 0 && <p className="hint">No categories yet.</p>}
            </div>
            <button type="button" className="btn ghost" onClick={() => setShowCats(false)}>Close</button>
          </div>
        </div>
      )}

      {edit && (
        <div className="scrim" onClick={(e) => e.target === e.currentTarget && setEdit(null)}>
          <form className="modal" role="dialog" aria-modal="true" onSubmit={save}>
            <h2>{edit.name}</h2>
            <ImageField type="item" id={edit.id} url={imgs.url("item", edit.id)} onChanged={imgs.reload} />
            <p className="hint">Changing the cost only affects new orders. Orders already placed keep the price they were made at.</p>
            <div className="addr"><label htmlFor="ecat">Category</label>
              <select className="line" id="ecat" value={edit.categoryId} onChange={(e) => setEdit({ ...edit, categoryId: e.target.value })}>
                <option value="">No category</option>
                {(cats.data || []).filter((c) => c.active || String(c.id) === String(edit.categoryId)).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
              </select></div>
            <div className="addr"><label htmlFor="eunit">Unit</label><input id="eunit" list="unitlist" value={edit.unit} onChange={(e) => setEdit({ ...edit, unit: e.target.value })} required /></div>
            <div className="addr"><label htmlFor="ecost">Cost per unit (&#8369;)</label><input id="ecost" value={edit.cost} inputMode="decimal" onChange={(e) => setEdit({ ...edit, cost: e.target.value })} required /></div>
            <div className="addr"><label htmlFor="ere">Warn when stock is at or below</label><input id="ere" value={edit.reorderLevel} inputMode="numeric" onChange={(e) => setEdit({ ...edit, reorderLevel: e.target.value })} /></div>
          <div className="grid2">
            <div className="addr">
              <label htmlFor="esku">SKU (optional)</label>
              <input id="esku" value={edit.sku} onChange={(ev) => setEdit({ ...edit, sku: ev.target.value })} maxLength={40} placeholder="HZL-01" />
              {fields.sku && <p className="err" role="alert">{fields.sku}</p>}
            </div>
            <div className="addr">
              <label htmlFor="ebar">Barcode (optional)</label>
              <input id="ebar" value={edit.barcode} onChange={(ev) => setEdit({ ...edit, barcode: ev.target.value })} maxLength={40} placeholder="Scan or type" />
              {fields.barcode && <p className="err" role="alert">{fields.barcode}</p>}
            </div>
          </div>
          <div className="addr">
            <label htmlFor="edesc">Description (optional)</label>
            <textarea id="edesc" className="line" rows={2} maxLength={500} value={edit.description} onChange={(ev) => setEdit({ ...edit, description: ev.target.value })} />
          </div>
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
    {importing && <ImportModal kind="items" onClose={() => setImporting(false)} onDone={(r) => { setMsg(`${r.added} items imported.`); reload(); }} />}
    {historyFor && <HistoryModal kind="item" id={historyFor.id} name={historyFor.name} unit={historyFor.unit} onClose={() => setHistoryFor(null)} />}
    {labelFor && <LabelModal name={labelFor.name} sku={labelFor.sku} barcode={labelFor.barcode} onClose={() => setLabelFor(null)} />}
    <datalist id="unitlist">{units.map((u) => <option key={u} value={u} />)}</datalist>
    </section>
  );
}
