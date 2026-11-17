import { useState } from "react";
import { api } from "../../shared/api.js";
import { useLoad } from "../../shared/hooks.js";
import { peso } from "../../shared/money.js";
import { useAuth } from "../../app/AuthContext.jsx";
import RecipeModal from "./RecipeModal.jsx";

const loadProducts = () => api("/api/admin/products");
const EMPTY = { name: "", categoryId: "", price: "", cost: "", art: "hot", hasSizes: false };

export default function ProductsPage() {
  const { data, error, loading, reload } = useLoad(loadProducts);
  const [form, setForm] = useState(EMPTY);
  const [fields, setFields] = useState({});
  const [msg, setMsg] = useState("");
  const [busy, setBusy] = useState(false);
  const [catName, setCatName] = useState("");
  const { can } = useAuth();
  const [recipeFor, setRecipeFor] = useState(null);

  const set = (k) => (e) => setForm({ ...form, [k]: e.target.type === "checkbox" ? e.target.checked : e.target.value });

  async function add(e) {
    e.preventDefault();
    setBusy(true);
    setFields({});
    setMsg("");
    try {
      await api("/api/admin/products", {
        method: "POST",
        body: { ...form, categoryId: form.categoryId ? Number(form.categoryId) : null },
      });
      setMsg(`${form.name.trim()} added to the menu.`);
      setForm(EMPTY);
      reload();
    } catch (err) {
      setFields(err.fields || {});
      setMsg(err.message);
    } finally {
      setBusy(false);
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
          <h2>Add a product</h2>
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
            <button className="btn warm" type="submit" disabled={busy}>Add product</button>
          </form>

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
                    <td><span className={`tag ${p.active ? "g" : "r"}`}>{p.active ? "On menu" : "Off menu"}</span></td>
                    <td className="rowbtns">
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
      {recipeFor && <RecipeModal product={recipeFor} onClose={() => setRecipeFor(null)} onSaved={(m) => { setRecipeFor(null); setMsg(m); }} />}
    </section>
  );
}
