import { useEffect, useState } from "react";
import { api } from "./api.js";
import { peso } from "./money.js";
import Modal from "./Modal.jsx";

const when = (iso) => new Date(iso).toLocaleString("en-PH", { timeZone: "Asia/Manila", dateStyle: "medium", timeStyle: "short" });
const KIND = { OPENING: "Opening", ADJUST: "Adjustment", WASTE: "Waste", PO_RECEIVE: "Received" };
const qty = (n) => (n / 1000).toLocaleString("en-PH", { maximumFractionDigits: 3 });
const money = (c) => (c == null ? "" : peso(c));

/** Price (or cost) history for a product or item, plus, for items, stock changes in every branch. */
export default function HistoryModal({ kind, id, name, unit, onClose }) {
  const [tab, setTab] = useState("price");
  const [price, setPrice] = useState(null);
  const [stock, setStock] = useState(null);
  const [err, setErr] = useState("");
  const base = `/api/admin/${kind === "product" ? "products" : "items"}/${id}`;

  useEffect(() => { api(`${base}/history`).then(setPrice).catch((e) => setErr(e.message)); }, [base]);
  useEffect(() => {
    if (kind === "item" && tab === "stock" && !stock) api(`${base}/movements`).then(setStock).catch((e) => setErr(e.message));
  }, [kind, tab, stock, base]);

  return (
    <Modal title={`History: ${name}`} wide onClose={onClose}>
      {kind === "item" && (
        <div className="tabbar" role="group" aria-label="History">
          <button className="chip" type="button" aria-pressed={tab === "price"} onClick={() => setTab("price")}>Cost changes</button>
          <button className="chip" type="button" aria-pressed={tab === "stock"} onClick={() => setTab("stock")}>Stock changes</button>
        </div>
      )}
      {err && <p className="err" role="alert">{err}</p>}
      {tab === "price" && (price == null ? <p role="status">Loading...</p> : (
        <div className="tw" style={{ maxHeight: 320 }}>
          <table className="dense">
            <thead><tr><th>When</th><th>By</th><th>What</th>{kind === "product" && <th className="n">Price</th>}<th className="n">Cost</th></tr></thead>
            <tbody>
              {price.map((p, n) => {
                const made = p.action.endsWith("CREATE");
                const pr = made ? money(p.newPrice) : `${money(p.oldPrice)} → ${money(p.newPrice)}`;
                const co = made ? money(p.newCost) : `${money(p.oldCost)} → ${money(p.newCost)}`;
                return (
                  <tr key={n}>
                    <td>{when(p.at)}</td><td>{p.by}</td><td>{made ? "Created" : "Changed"}</td>
                    {kind === "product" && <td className="n">{pr}</td>}
                    <td className="n">{co}</td>
                  </tr>
                );
              })}
              {price.length === 0 && <tr><td colSpan="5" className="emptyrow">No changes recorded yet. Changes made from now on show here.</td></tr>}
            </tbody>
          </table>
        </div>
      ))}
      {tab === "stock" && (stock == null ? <p role="status">Loading...</p> : (
        <div className="tw" style={{ maxHeight: 320 }}>
          <table className="dense">
            <thead><tr><th>When</th><th>Branch</th><th>Type</th><th className="n">Qty{unit ? ` (${unit})` : ""}</th><th>By</th><th>Note</th></tr></thead>
            <tbody>
              {stock.map((m) => (
                <tr key={m.id}>
                  <td>{when(m.at)}</td><td>{m.branch}</td><td>{KIND[m.kind] || m.kind}</td>
                  <td className="n">{m.qty > 0 ? "+" : ""}{qty(m.qty)}</td><td>{m.by}</td>
                  <td>{[m.ref, m.reason].filter(Boolean).join(" · ")}</td>
                </tr>
              ))}
              {stock.length === 0 && <tr><td colSpan="6" className="emptyrow">No stock changes yet.</td></tr>}
            </tbody>
          </table>
        </div>
      ))}
      <div className="opts"><button className="btn ghost" type="button" onClick={onClose}>Close</button></div>
    </Modal>
  );
}
