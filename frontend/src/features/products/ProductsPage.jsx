import { useState } from "react";
import { api } from "../../shared/api.js";
import { useLoad } from "../../shared/hooks.js";
import { peso } from "../../shared/money.js";
import { useAuth } from "../../app/AuthContext.jsx";
import RecipeModal from "./RecipeModal.jsx";
import OptionsModal from "./OptionsModal.jsx";
import Modal from "../../shared/Modal.jsx";
import ImageField, { Thumb } from "../../shared/ImageField.jsx";
import { useImages } from "../../shared/images.js";

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
  const [formOpen, setFormOpen] = useState(false);
  const [setupTab, setSetupTab] = useState(null);   // null = closed, else "headers" | "categories" | "addons"
  const imgs = useImages();
  const [search, setSearch] = useState("");
  const [catFilter, setCatFilter] = useState("");

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
      setFormOpen(false);
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
    setFormOpen(true);
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

  const q = search.trim().toLowerCase();
  const shown = products.filter((p) =>
    (!catFilter || String(p.categoryId) === catFilter) && (!q || p.name.toLowerCase().includes(q)));
  const closeForm = () => { setFormOpen(false); setEditId(null); setForm(EMPTY); setFields({}); };
  const canSetup = can("product_headers_view") || can("product_categories_edit") || can("product_addons_view");

  return (
    <section className="fitpage">
      <header className="pghead">
        <div>
          <h1>Products</h1>
          <p>What every branch sells. Switch a product off and it leaves the POS.</p>
        </div>
        <div className="toolbar">
          <input className="searchbox" type="search" aria-label="Search products" placeholder="Search products" value={search} onChange={(e) => setSearch(e.target.value)} />
          <select aria-label="Show category" value={catFilter} onChange={(e) => setCatFilter(e.target.value)}>
            <option value="">All categories</option>
            {categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
          {canSetup && <button className="btn ghost sm" type="button" onClick={() => setSetupTab(can("product_headers_view") ? "headers" : can("product_categories_edit") ? "categories" : "addons")}>Menu setup</button>}
          <button className="btn warm sm" type="button" onClick={() => { setEditId(null); setForm(EMPTY); setFields({}); setMsg(""); setFormOpen(true); }}>+ Add product</button>
        </div>
      </header>
      {msg && !formOpen && <p className="notice" role="status">{msg}</p>}

      <div className="sec fill">
        <p className="hint count">{shown.length} of {products.length} products</p>
        {error && <p className="err" role="alert">{error}</p>}
        {loading && !data ? (
          <p role="status">Loading...</p>
        ) : (
          <div className="tw">
            <table className="dense">
              <thead>
                <tr><th></th><th>Product</th><th>Category</th><th className="n">Price</th><th>Status</th><th></th></tr>
              </thead>
              <tbody>
                {shown.map((p) => (
                  <tr key={p.id}>
                    <td className="thc"><Thumb url={imgs.url("product", p.id)} alt="" /></td>
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
                {data && shown.length === 0 && (
                  <tr><td colSpan="6" className="emptyrow">{products.length === 0 ? "No products yet. Add the first one." : "No products match."}</td></tr>
                )}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {formOpen && (
        <Modal title={editId ? "Edit product" : "Add a product"} onClose={closeForm} onSubmit={add}>
          <div className="addr">
            <label htmlFor="pname">Name</label>
            <input id="pname" value={form.name} onChange={set("name")} placeholder="Hazelnut Latte" required autoFocus />
            {fields.name && <p className="err" role="alert">{fields.name}</p>}
          </div>
          <ImageField type="product" id={editId} url={editId ? imgs.url("product", editId) : null} onChanged={imgs.reload} />
          <div className="grid2">
            <div className="addr">
              <label htmlFor="pcat">Category</label>
              <select className="line" id="pcat" value={form.categoryId} onChange={set("categoryId")} required>
                <option value="">Choose a category</option>
                {categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
              </select>
              {fields.categoryId && <p className="err" role="alert">{fields.categoryId}</p>}
            </div>
            <div className="addr">
              <label htmlFor="part">Picture on the POS tile</label>
              <select className="line" id="part" value={form.art} onChange={set("art")}>
                {arts.map((a) => <option key={a} value={a}>{a}</option>)}
              </select>
            </div>
            <div className="addr">
              <label htmlFor="pprice">Price (&#8369;)</label>
              <input id="pprice" value={form.price} onChange={set("price")} inputMode="decimal" placeholder="99.00" required />
              {fields.price && <p className="err" role="alert">{fields.price}</p>}
            </div>
            <div className="addr">
              <label htmlFor="pcost">Cost (&#8369;)</label>
              <input id="pcost" value={form.cost} onChange={set("cost")} inputMode="decimal" placeholder="40.00" />
              {fields.cost && <p className="err" role="alert">{fields.cost}</p>}
            </div>
          </div>
          <label className="check">
            <input type="checkbox" checked={form.hasSizes} onChange={set("hasSizes")} /> Offers Regular and Large (+&#8369;20)
          </label>
          {editId && (
            <label className="check">
              <input type="checkbox" checked={form.published} onChange={set("published")} /> Published (untick to keep it as a draft)
            </label>
          )}
          {msg && <p className="err" role="alert">{msg}</p>}
          <div className="opts">
            <button className="btn ghost" type="button" onClick={closeForm}>Cancel</button>
            <button className="btn warm" type="submit" disabled={busy} style={{ flex: 1 }}>{editId ? "Save changes" : "Add product"}</button>
          </div>
        </Modal>
      )}

      {setupTab && (
        <Modal title="Menu setup" wide onClose={() => setSetupTab(null)}>
          <div className="tabbar" role="group" aria-label="Setup section">
            {can("product_headers_view") && <button className="chip" aria-pressed={setupTab === "headers"} onClick={() => setSetupTab("headers")}>Headers</button>}
            <button className="chip" aria-pressed={setupTab === "categories"} onClick={() => setSetupTab("categories")}>Categories</button>
            {can("product_addons_view") && <button className="chip" aria-pressed={setupTab === "addons"} onClick={() => setSetupTab("addons")}>Add-ons</button>}
          </div>
          {msg && <p className="notice" role="status">{msg}</p>}

          {setupTab === "headers" && (
            <>
              <p className="hint">Headers group categories on the POS, for example Drinks and Food.</p>
              <div className="catlist">
                {headers.map((h) => (
                  <div className="opts" key={h.id}>
                    <b style={{ flex: 1 }}>{h.name}{!h.active && " (hidden)"}</b>
                    {can("product_headers_edit") && (
                      <button className="btn ghost sm" type="button" onClick={() => saveHeader(h, { active: !h.active })}>{h.active ? "Hide" : "Show"}</button>
                    )}
                  </div>
                ))}
              </div>
              {can("product_headers_add") && (
                <form onSubmit={addHeader} className="opts">
                  <input aria-label="New header" id="hdrname" value={headerName} onChange={(e) => setHeaderName(e.target.value)} placeholder="New header, e.g. Drinks" required style={{ flex: 1 }} />
                  <button className="btn ghost sm" type="submit">Add header</button>
                </form>
              )}
            </>
          )}

          {setupTab === "categories" && (
            <>
              <div className="catlist">
                {can("product_categories_edit") && allCategories.map((c) => (
                  <div className="opts" key={c.id}>
                    <span style={{ flex: 1 }}>{c.name}{!c.active && " (hidden)"}</span>
                    <select aria-label={`Header for ${c.name}`} value={c.headerId ?? ""}
                      onChange={(e) => saveCategory(c, { headerId: e.target.value ? Number(e.target.value) : null })}>
                      <option value="">No header</option>
                      {headers.map((h) => <option key={h.id} value={h.id}>{h.name}</option>)}
                    </select>
                    <button className="btn ghost sm" type="button" onClick={() => saveCategory(c, { active: !c.active })}>{c.active ? "Hide" : "Show"}</button>
                  </div>
                ))}
              </div>
              <form onSubmit={addCategory} className="opts">
                <input aria-label="New category" id="catname" value={catName} onChange={(e) => setCatName(e.target.value)} placeholder="New category, e.g. Pastries" required style={{ flex: 1 }} />
                <button className="btn ghost sm" type="submit">Add category</button>
              </form>
            </>
          )}

          {setupTab === "addons" && (
            <>
              <div className="catlist">
                {addons.map((a) => (
                  <div className="opts" key={a.id}>
                    <span style={{ flex: 1 }}>{a.name} &middot; {peso(a.price)}{!a.active && " (off)"}</span>
                    {can("product_addons_edit") && (
                      <button className="btn ghost sm" type="button" onClick={() => saveAddon(a, { active: !a.active })}>{a.active ? "Switch off" : "Switch on"}</button>
                    )}
                  </div>
                ))}
                {addons.length === 0 && <p className="hint">No add-ons yet.</p>}
              </div>
              {can("product_addons_add") && (
                <form onSubmit={addAddon} className="opts">
                  <input aria-label="New add-on" id="addonname" value={addonForm.name} onChange={(e) => setAddonForm({ ...addonForm, name: e.target.value })} placeholder="New add-on, e.g. Extra shot" required style={{ flex: 1 }} />
                  <input aria-label="Add-on price" id="addonprice" value={addonForm.price} onChange={(e) => setAddonForm({ ...addonForm, price: e.target.value })} inputMode="decimal" placeholder="₱20" required style={{ width: 80 }} />
                  <button className="btn ghost sm" type="submit">Add</button>
                </form>
              )}
            </>
          )}
          <button type="button" className="btn ghost" onClick={() => setSetupTab(null)}>Close</button>
        </Modal>
      )}

      {optionsFor && <OptionsModal product={optionsFor} addons={addons} onClose={() => setOptionsFor(null)} onSaved={(m) => { setOptionsFor(null); setMsg(m); }} />}
      {recipeFor && <RecipeModal product={recipeFor} onClose={() => setRecipeFor(null)} onSaved={(m) => { setRecipeFor(null); setMsg(m); }} />}
    </section>
  );
}
