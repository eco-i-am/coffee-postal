import { useCallback, useState } from "react";
import { api } from "../../shared/api.js";
import { useLoad } from "../../shared/hooks.js";
import { peso, totals } from "../../shared/money.js";
import TileArt from "./tileArt.jsx";
import { addToCart, cartGross, changeQty, newOrderId } from "./cart.js";
import ItemModal from "./ItemModal.jsx";
import PayModal from "./PayModal.jsx";
import Receipt from "./Receipt.jsx";

export const DISCOUNTS = [["NONE", "None"], ["SENIOR_PWD", "Senior / PWD"], ["PROMO10", "Promo 10%"]];
export const TYPES = [["DINE_IN", "Dine-in"], ["TAKEOUT", "Takeout"], ["DELIVERY", "Delivery"]];

export default function PosPage() {
  const loadMenu = useCallback(() => api("/api/pos/menu"), []);
  const { data: menu, error, loading } = useLoad(loadMenu);

  const [cat, setCat] = useState("All");
  const [cart, setCart] = useState([]);
  const [otype, setOtype] = useState("DINE_IN");
  const [disc, setDisc] = useState("NONE");
  const [orderId, setOrderId] = useState(null);
  const [modal, setModal] = useState(null); // { kind: "item", item } | { kind: "pay" } | { kind: "receipt", data }

  const t = totals(cartGross(cart), disc);
  const items = (menu?.items || []).filter((m) => cat === "All" || m.category === cat);

  function edit(fn) {
    setCart(fn);
    setOrderId(null); // the order changed, so it is a new sale
  }

  function tap(item) {
    if (!item.sizes.length && !item.addons.length) edit((c) => addToCart(c, item, null, []));
    else setModal({ kind: "item", item });
  }

  function openPay() {
    if (!orderId) setOrderId(newOrderId());
    setModal({ kind: "pay" });
  }

  function paid(res, method, tendered) {
    setModal({ kind: "receipt", data: { res, method, tendered, cart, otype, disc } });
    setCart([]);
    setDisc("NONE");
    setOrderId(null);
  }

  return (
    <section>
      <header className="pghead">
        <div>
          <h1>Point of Sale</h1>
          <p>Tap an item to start an order. Prices include 12% VAT.</p>
        </div>
      </header>
      {error && <p className="notice" role="alert">{error}</p>}

      <div className="tabs" role="group" aria-label="Categories">
        {["All", ...(menu?.categories || [])].map((c) => (
          <button key={c} className="chip" aria-pressed={c === cat} onClick={() => setCat(c)}>{c}</button>
        ))}
      </div>

      <div className="pos">
        <div className="menu">
          {items.map((m) => (
            <button key={m.id} className="tile" style={{ "--c": m.color }} onClick={() => tap(m)}>
              <span className="art"><TileArt kind={m.art} /></span>
              <b>{m.name}</b>
              <span className="pr"><small>&#8369;</small>{Math.floor(m.price / 100)}</span>
            </button>
          ))}
          {!loading && items.length === 0 && !error && (
            <div className="empty">No items on the menu yet. Ask HQ to add products.</div>
          )}
        </div>

        <div className="cart">
          <div className="hd">
            <h2>New order</h2>
            <button className="btn ghost sm" onClick={() => { setCart([]); setDisc("NONE"); setOrderId(null); }}>Clear</button>
          </div>
          <div className="seg" role="group" aria-label="Order type">
            {TYPES.map(([k, label]) => (
              <button key={k} className="chip" aria-pressed={otype === k} onClick={() => setOtype(k)}>{label}</button>
            ))}
          </div>
          <div>
            {cart.length === 0 && <div className="empty">Nothing on this order yet.<br />Tap a drink or snack to add it.</div>}
            {cart.map((l) => (
              <div className="line" key={l.key}>
                <div><b>{l.name}</b></div>
                <div><b>{peso(l.unit * l.qty)}</b></div>
                <small>{l.opt}</small>
                <div className="qty">
                  <button aria-label={`Less ${l.name}`} onClick={() => edit((c) => changeQty(c, l.key, -1))}>&minus;</button>
                  {l.qty}
                  <button aria-label={`More ${l.name}`} onClick={() => edit((c) => changeQty(c, l.key, 1))}>+</button>
                </div>
              </div>
            ))}
          </div>
          <label className="discrow">
            Discount
            <select value={disc} onChange={(e) => { setDisc(e.target.value); setOrderId(null); }}>
              {DISCOUNTS.map(([k, label]) => <option key={k} value={k}>{label}</option>)}
            </select>
          </label>
          {t.discount > 0 && <div className="tot"><span>Discount</span><span>&minus;{peso(t.discount)}</span></div>}
          <div className="tot vatrow"><span>incl. VAT 12%</span><span>{peso(t.vat)}</span></div>
          <div className="tot big"><span>Total</span><span>{peso(t.pay)}</span></div>
          <button className="btn brand chargebtn" disabled={cart.length === 0} onClick={openPay}>Charge {peso(t.pay)}</button>
        </div>
      </div>

      {modal && (
        <div className="scrim" onClick={(e) => e.target === e.currentTarget && setModal(null)}>
          <div className="modal" role="dialog" aria-modal="true">
            {modal.kind === "item" && (
              <ItemModal item={modal.item} onCancel={() => setModal(null)}
                onAdd={(size, addons) => { edit((c) => addToCart(c, modal.item, size, addons)); setModal(null); }} />
            )}
            {modal.kind === "pay" && (
              <PayModal cart={cart} otype={otype} disc={disc} orderId={orderId} total={t.pay}
                onBack={() => setModal(null)} onPaid={paid} />
            )}
            {modal.kind === "receipt" && <Receipt {...modal.data} onDone={() => setModal(null)} />}
          </div>
        </div>
      )}
    </section>
  );
}
