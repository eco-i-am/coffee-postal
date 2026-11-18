import { useState } from "react";
import { api } from "../../shared/api.js";
import { useLoad } from "../../shared/hooks.js";
import { peso } from "../../shared/money.js";
import { useAuth } from "../../app/AuthContext.jsx";
import RecipeModal from "./RecipeModal.jsx";
import OptionsModal from "./OptionsModal.jsx";

const loadProducts = () => api("/api/admin/products");
const loadAddons = () => api("/api/admin/addons").catch(() => []);
const EMPTY = { name: "", categoryId: "", price: "", cost: "", art: "hot", hasSizes: false, published: true };
const pesoText = (c) => (c / 100).toFixed(2);

export default function ProductsPage() {
  const { data, error, loading, reload } = useLoad(loadProducts);
  const [form, setForm] = useState(EMPTY);
  const [fields, setFields] = useState({});
  const [msg, setMsg] = useState("");
  const [busy, setBusy] = useState(false);
  const [catName, setCatName] = useState("");
  const { can } = useAuth();
  const [recipeFor, setRecipeFor] = useState(null);
  const [editId, setEditId] = useState(null);
  const addonsLoad = useLoad(loadAddons);
  const addons = addonsLoad.data || [];
  const [optionsFor, setOptionsFor] = useState(null);
  const [addonForm, setAddonForm] = useState({ name: "", price: "" });
  const [headerName, setHeaderName] = useState("");

  const set = (k) => (e) => setForm({ ...form, [k]: e.target.type === "checkbox" ? e.target.checked : e.target.value });

  async function add(e) {
    e.preventDefault();
    setBusy(true);
    setFields({});
    setMsg("");
    try {
      const body = { ...form, categoryId: form.categoryId ? Number(form.categoryId) : null };
      if (editId) await api(`/api/admin/products/${editId}`, { method: "PUT", body });
      else await api("/api/admin/products", { method: "POST", body });
      setMsg(editId ? `${form.name.trim()} saved.` : `${form.name.trim()} added to the menu.`);
      setForm(EMPTY);
      setEditId(null);
      reload();
    } catch (err) {
      setFields(err.fields || {});
      setMsg(err.message);
    } finally {
      setBusy(false);
    }
  }

  function startEdit(p) {
    setEditId(p.id);
    setFields({});
    setMsg("");
    setForm({ name: p.name, categoryId: String(p.categoryId), price: pesoText(p.price), cost: pesoText(p.cost),
      art: p.art, hasSizes: p.hasSizes, published: p.published });
  }

  async function addHeader(e) {
    e.preventDefault();
    setMsg("");
    try {
      await api("/api/admin/headers", { method: "POST", body: { name: headerName } });
      setMsg(`${headerName.trim()} header added.`);
      setHeaderName("");
      reload();
    } catch (err) {
      setMsg(err.message);
    }
  }

  async function saveCategory(c, patch) {
    setMsg("");
    try {
      await api(`/api/admin/categories/${c.id}`, { method: "PUT",
        body: { name: c.name, headerId: c.headerId, sortOrder: c.sortOrder, active: c.active, ...patch } });
      reload();
    } catch (err) {
      setMsg(err.message);
    }
  }

  async function addAddon(e) {
    e.preventDefault();
    setMsg("");
    try {
      await api("/api/admin/addons", { method: "POST", body: { ...addonForm, active: true } });
      setMsg(`${addonForm.name.trim()} add-on added.`);
      setAddonForm({ name: "", price: "" });
      addonsLoad.reload();
    } catch (err) {
      setMsg(err.message);
    }
  }

  async function saveAddon(a, patch) {
    setMsg("");
    try {
      await api(`/api/admin/addons/${a.id}`, { method: "PUT",
        body: { name: a.name, price: (a.price / 100).toFixed(2), active: a.active, ...patch } });
      addonsLoad.reload();
    } catch (err) {
      setMsg(err.message);
    }
  }

  async function saveHeader(h, patch) {
    setMsg("");
    try {
      await api(`/api/admin/headers/${h.id}`, { method: "PUT", body: { name: h.name, sortOrder: h.sortOrder, active: h.active, ...patch } });
      reload();
    } catch (err) {
      setMsg(err.message);
    }
  }

  async function toggle(p) {
    setMsg("");
    try {
      await api(`/api/admin/products/${p.id}/toggle`, { method: "POST" });
      reload();
    } catch (err) {
      setMsg(err.message);
    }
  }

  async function addCategory(e) {
    e.preventDefault();
    setMsg("");
    try {
      await api("/api/admin/categories", { method: "POST", body: { name: catName } });
      setMsg(`${catName.trim()} category added.`);
      setCatName("");
      reload();
    } catch (err) {
      setMsg(err.message);
    }
  }

  const products = data?.products || [];
  const categories = data?.categories || [];
  const arts = data?.arts || [];
  const headers = data?.headers || [];
  const allCategories = data?.allCategories || [];

  return (
    <section>
      <header className="pghead">
        <div>
          <h1>Products</h1>
          <p>What every branch sells. Switch a product off and it leaves the POS.</p>
        </div>
      </header>
      {msg && <p className="notice" role="status">{msg}</p>}

      <div className="two">
        <div className="sec">
          <h2>{editId ? "Edit product" : "Add a product"}</h2>
          <form onSubmit={add} className="stack" noValidate>
            <div className="addr">
              <label htmlFor="pname">Name</label>
              <input id="pname" value={form.name} onChange={set("name")} placeholder="Hazelnut Latte" required />
              {fields.name && <p className="err" role="alert">{fields.name}</p>}
            </div>
            <div className="addr">
              <label htmlFor="pcat">Category</label>
              <select className="line" id="pcat" value={form.categoryId} onChange={set("categoryId")} required>
                <option value="">Choose a category</option>
                {categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
              </select>
              {fields.categoryId && <p className="err" role="alert">{fields.categoryId}</p>}
            </div>
            <div className="opts">
              <div className="addr" style={{ flex: 1, minWidth: 120 }}>
                <label htmlFor="pprice">Price (&#8369;)</label>
                <input id="pprice" value={form.price} onChange={set("price")} inputMode="decimal" placeholder="99.00" required />
                {fields.price && <p className="err" role="alert">{fields.price}</p>}
              </div>
              <div className="addr" style={{ flex: 1, minWidth: 120 }}>
                <label htmlFor="pcost">Cost (&#8369;)</label>
                <input id="pcost" value={form.cost} onChange={set("cost")} inputMode="decimal" placeholder="40.00" />
                {fields.cost && <p className="err" role="alert">{fields.cost}</p>}
              </div>
            </div>
            <div className="addr">
              <label htmlFor="part">Picture on the POS tile</label>
              <select className="line" id="part" value={form.art} onChange={set("art")}>
                {arts.map((a) => <option key={a} value={a}>{a}</option>)}
              </select>
            </div>
            <label className="check">
              <input type="checkbox" checked={form.hasSizes} onChange={set("hasSizes")} /> Offers Regular and Large (+&#8369;20)
            </label>
            {editId && (
              <label className="check">
                <input type="checkbox" checked={form.published} onChange={set("published")} /> Published (untick to keep it as a draft)
              </label>
            )}
            <div className="opts">
              <button className="btn warm" type="submit" disabled={busy}>{editId ? "Save changes" : "Add product"}</button>
              {editId && <button className="btn ghost" type="button" onClick={() => { setEditId(null); setForm(EMPTY); setFields({}); }}>Cancel</button>}
            </div>
          </form>

          {can("product_headers_view") && (
            <>
              <h2 style={{ marginTop: 24 }}>Headers</h2>
              <p className="hint">Headers group categories on the POS, for example Drinks and Food.</p>
              {headers.map((h) => (
                <div className="opts" key={h.id}>
                  <b style={{ flex: 1 }}>{h.name}</b>
                  {can("product_headers_edit") && (
                    <button className="btn ghost sm" type="button" onClick={() => saveHeader(h, { active: !h.active })}>{h.active ? "Hide" : "Show"}</button>
                  )}
                </div>
              ))}
              {can("product_headers_add") && (
                <form onSubmit={addHeader} className="opts">
                  <div className="addr" style={{ flex: 1 }}>
                    <label htmlFor="hdrname">New header</label>
                    <input id="hdrname" value={headerName} onChange={(e) => setHeaderName(e.target.value)} placeholder="Drinks" required />
                  </div>
                  <button className="btn ghost" type="submit" style={{ alignSelf: "end" }}>Add</button>
                </form>
              )}
            </>
          )}

          {can("product_categories_edit") && allCategories.length > 0 && (
            <>
              <h2 style={{ marginTop: 24 }}>Categories</h2>
              {allCategories.map((c) => (
                <div className="opts" key={c.id}>
                  <span style={{ flex: 1 }}>{c.name}{!c.active && " (hidden)"}</span>
                  <select className="line" aria-label={`Header for ${c.name}`} value={c.headerId ?? ""}
                    onChange={(e) => saveCategory(c, { headerId: e.target.value ? Number(e.target.value) : null })}>
                    <option value="">No header</option>
                    {headers.map((h) => <option key={h.id} value={h.id}>{h.name}</option>)}
                  </select>
                  <button className="btn ghost sm" type="button" onClick={() => saveCategory(c, { active: !c.active })}>{c.active ? "Hide" : "Show"}</button>
                </div>
              ))}
            </>
          )}

          {can("product_addons_view") && (
            <>
              <h2 style={{ marginTop: 24 }}>Add-ons</h2>
              {addons.map((a) => (
                <div className="opts" key={a.id}>
                  <span style={{ flex: 1 }}>{a.name} &middot; {peso(a.price)}{!a.active && " (off)"}</span>
                  {can("product_addons_edit") && (
                    <button className="btn ghost sm" type="button" onClick={() => saveAddon(a, { active: !a.active })}>{a.active ? "Switch off" : "Switch on"}</button>
                  )}
                </div>
              ))}
              {can("product_addons_add") && (
                <form onSubmit={addAddon} className="opts">
                  <div className="addr" style={{ flex: 1 }}>
                    <label htmlFor="addonname">New add-on</label>
                    <input id="addonname" value={addonForm.name} onChange={(e) => setAddonForm({ ...addonForm, name: e.target.value })} placeholder="Extra shot" required />
                  </div>
                  <div className="addr" style={{ width: 100 }}>
                    <label htmlFor="addonprice">Price (&#8369;)</label>
                    <input id="addonprice" value={addonForm.price} onChange={(e) => setAddonForm({ ...addonForm, price: e.target.value })} inputMode="decimal" placeholder="20" required />
                  </div>
                  <button className="btn ghost" type="submit" style={{ alignSelf: "end" }}>Add</button>
                </form>
              )}
            </>
          )}

          <h2 style={{ marginTop: 24 }}>Add a category</h2>
          <form onSubmit={addCategory} className="opts">
            <div className="addr" style={{ flex: 1 }}>
              <label htmlFor="catname">Name</label>
              <input id="catname" value={catName} onChange={(e) => setCatName(e.target.value)} placeholder="Pastries" required />
            </div>
            <button className="btn ghost" type="submit" style={{ alignSelf: "end" }}>Add</button>
          </form>
        </div>

        <div className="sec tw">
          <h2>Menu</h2>
          {error && <p className="err" role="alert">{error}</p>}
          {loading && !data ? (
            <p role="status">Loading...</p>
          ) : (
            <table>
              <thead>
                <tr><th>Product</th><th>Category</th><th className="n">Price</th><th>Status</th><th></th></tr>
              </thead>
              <tbody>
                {products.map((p) => (
                  <tr key={p.id}>
                    <td><b>{p.name}</b></td>
                    <td>{p.category}</td>
                    <td className="n">{peso(p.price)}</td>
                    <td><span className={`tag ${p.active && p.published ? "g" : "r"}`}>{!p.published ? "Draft" : p.active ? "On menu" : "Off menu"}</span></td>
                    <td className="rowbtns">
                      {can("products_edit") && <button className="btn ghost sm" type="button" onClick={() => startEdit(p)}>Edit</button>}
                      {can("products_edit") && <button className="btn ghost sm" type="button" onClick={() => setOptionsFor(p)}>Branches</button>}
                      {can("products_edit") && <button className="btn ghost sm" type="button" onClick={() => setRecipeFor(p)}>Recipe</button>}
                      <button className="btn ghost sm" type="button" onClick={() => toggle(p)}>{p.active ? "Switch off" : "Switch on"}</button>
                    </td>
                  </tr>
                ))}
                {products.length === 0 && (
                  <tr><td colSpan="5" className="emptyrow">No products yet. Add the first one.</td></tr>
                )}
              </tbody>
            </table>
          )}
        </div>
      </div>
      {optionsFor && <OptionsModal product={optionsFor} addons={addons} onClose={() => setOptionsFor(null)} onSaved={(m) => { setOptionsFor(null); setMsg(m); }} />}
      {recipeFor && <RecipeModal product={recipeFor} onClose={() => setRecipeFor(null)} onSaved={(m) => { setRecipeFor(null); setMsg(m); }} />}
    </section>
  );
}
